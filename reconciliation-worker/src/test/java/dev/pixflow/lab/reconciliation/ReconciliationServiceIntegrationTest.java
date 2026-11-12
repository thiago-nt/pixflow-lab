package dev.pixflow.lab.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pixflow.lab.contracts.PspOutcome;
import dev.pixflow.lab.contracts.PspTransactionView;
import dev.pixflow.lab.contracts.SimulationScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "pixflow.reconciliation.scheduling-enabled=false",
        "pixflow.reconciliation.owner-prefix=test-reconciliation",
        "pixflow.reconciliation.lease-ttl=10s",
        "pixflow.reconciliation.poll-delay=1h",
        "pixflow.reconciliation.batch-size=10",
        "pixflow.reconciliation.max-attempts=3",
        "pixflow.reconciliation.base-backoff=10ms",
        "pixflow.reconciliation.max-backoff=100ms",
        "pixflow.reconciliation.jitter=0ms",
        "pixflow.reconciliation.result-topic=payment.result.v1"
})
@Testcontainers(disabledWithoutDocker = true)
class ReconciliationServiceIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine")
            .withDatabaseName("pixflow").withUsername("pixflow_app").withPassword("test-only");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ReconciliationService service;
    @Autowired ReconciliationProperties properties;
    @Autowired ObjectMapper json;
    @MockitoBean PspLookupClient psp;
    @MockitoBean AfterPspLookupHook afterLookup;

    @BeforeAll
    static void schema() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            for (var migration : List.of("V001__create_payment_core.sql", "V002__payment_api_pending_state.sql",
                    "V003__outbox_claim_ownership.sql", "V004__worker_and_psp_simulator.sql",
                    "V005__payment_worker_lease_fencing.sql", "V006__reconciliation_worker.sql")) {
                var path = Path.of(System.getProperty("user.dir"), "infra", "postgres", "migrations", migration);
                if (!Files.exists(path)) path = Path.of(System.getProperty("user.dir"), "..", "infra", "postgres", "migrations", migration);
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(path));
            }
        }
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE reconciliation_audit,reconciliation_jobs,payment_lease_audit,worker_deferred_events,payment_audit,outbox_events,inbox_processed,idempotency_keys,psp_simulator.transactions,payments RESTART IDENTITY CASCADE");
        reset(psp, afterLookup);
        doNothing().when(afterLookup).afterLookup(any(), any());
    }

    @Test
    void timeoutAfterPspApprovalRecoversUnknownToSucceededWithAuditAndOutbox() {
        var paymentId = insertUnknown("approved");
        when(psp.lookup(reference(paymentId))).thenReturn(PspLookupResult.approved(view(paymentId, PspOutcome.APPROVED)));

        assertThat(service.runBatch()).isEqualTo(1);

        assertThat(status(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(jobResult(paymentId)).isEqualTo("CONFIRMED_APPROVED");
        assertThat(count("reconciliation_audit", paymentId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_audit WHERE payment_id=? AND reason='RECONCILED_APPROVED'", Integer.class, paymentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND event_type='payment.result'", Integer.class, paymentId)).isEqualTo(1);
        verify(psp).lookup(reference(paymentId));
    }

    @Test
    void timeoutAfterPspRejectionRecoversUnknownToFailed() {
        var paymentId = insertUnknown("rejected");
        when(psp.lookup(reference(paymentId))).thenReturn(PspLookupResult.rejected(view(paymentId, PspOutcome.REJECTED)));

        service.runBatch();

        assertThat(status(paymentId)).isEqualTo("FAILED");
        assertThat(jobResult(paymentId)).isEqualTo("CONFIRMED_REJECTED");
        assertThat(jdbc.queryForObject("SELECT result_code FROM payments WHERE payment_id=?", String.class, paymentId)).isEqualTo("RECONCILED_REJECTED");
    }

    @Test
    void temporaryPspFailureKeepsUnknownAndSchedulesSafeRetry() {
        var paymentId = insertUnknown("temporary");
        when(psp.lookup(anyString())).thenReturn(PspLookupResult.temporaryFailure("PSP unavailable"));

        service.runBatch();

        assertThat(status(paymentId)).isEqualTo("UNKNOWN");
        assertThat(jobResult(paymentId)).isEqualTo("TEMPORARY_FAILURE");
        assertThat(jdbc.queryForObject("SELECT next_attempt_at > last_attempt_at FROM reconciliation_jobs WHERE payment_id=?", Boolean.class, paymentId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT lease_owner IS NULL FROM payments WHERE payment_id=?", Boolean.class, paymentId)).isTrue();
    }

    @Test
    void missingReferenceNeverBecomesRejectedAndEventuallyMovesToManualReview() {
        var paymentId = insertUnknown("missing");
        when(psp.lookup(anyString())).thenReturn(PspLookupResult.notFound());

        for (int attempt = 1; attempt <= 3; attempt++) {
            service.runBatch();
            if (attempt < 3) jdbc.update("UPDATE reconciliation_jobs SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE payment_id=?", paymentId);
        }

        assertThat(status(paymentId)).isEqualTo("MANUAL_REVIEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM psp_simulator.transactions WHERE payment_id=?", Integer.class, paymentId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_audit WHERE payment_id=? AND outcome='NOT_FOUND'", Integer.class, paymentId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_audit WHERE payment_id=? AND outcome='MANUAL_REVIEW'", Integer.class, paymentId)).isEqualTo(1);
    }

    @Test
    void restartAfterLookupRecoversExpiredClaimWithoutIssuingFinancialOperation() {
        var paymentId = insertUnknown("restart");
        service.discoverUnknownPayments();
        var first = service.claimNext().orElseThrow();
        when(psp.lookup(anyString())).thenReturn(PspLookupResult.approved(view(paymentId, PspOutcome.APPROVED)));
        doThrow(new SimulatedProcessCrash()).when(afterLookup).afterLookup(any(), any());

        assertThatThrownBy(() -> service.reconcile(first)).isInstanceOf(SimulatedProcessCrash.class);
        jdbc.update("UPDATE reconciliation_jobs SET claimed_until=clock_timestamp()-interval '1 second' WHERE payment_id=?", paymentId);
        jdbc.update("UPDATE payments SET lease_until=clock_timestamp()-interval '1 second' WHERE payment_id=?", paymentId);
        reset(afterLookup);
        doNothing().when(afterLookup).afterLookup(any(), any());

        var restarted = service("reconciliation-restarted");
        assertThat(restarted.reconcileNext()).isTrue();
        assertThat(status(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM reconciliation_jobs WHERE payment_id=?", Integer.class, paymentId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT max(fencing_token) > min(fencing_token) FROM reconciliation_audit WHERE payment_id=?", Boolean.class, paymentId)).isTrue();
    }

    @Test
    void twoInstancesCannotClaimSameReconciliation() throws Exception {
        var paymentId = insertUnknown("race");
        service.discoverUnknownPayments();
        var one = service("reconciliation-A");
        var two = service("reconciliation-B");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> { ready.countDown(); start.await(); return one.claimNext(); });
            var b = executor.submit(() -> { ready.countDown(); start.await(); return two.claimNext(); });
            ready.await();
            start.countDown();
            assertThat(List.of(a.get(), b.get()).stream().filter(java.util.Optional::isPresent).count()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_audit WHERE payment_id=? AND outcome='CLAIMED'", Integer.class, paymentId)).isEqualTo(1);
    }

    @Test
    void terminalResultCannotBeOverwritten() {
        var paymentId = insertUnknown("terminal");
        service.discoverUnknownPayments();
        var claim = service.claimNext().orElseThrow();
        jdbc.update("UPDATE payments SET status='SUCCEEDED' WHERE payment_id=?", paymentId);
        when(psp.lookup(anyString())).thenReturn(PspLookupResult.rejected(view(paymentId, PspOutcome.REJECTED)));

        service.reconcile(claim);

        assertThat(status(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(jobResult(paymentId)).isEqualTo("TERMINAL_PRESERVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=?", Integer.class, paymentId)).isZero();
    }

    @Test
    void obsoleteFencingTokenIsRejected() {
        var paymentId = insertUnknown("stale");
        service.discoverUnknownPayments();
        var claim = service.claimNext().orElseThrow();
        jdbc.update("UPDATE payments SET lease_owner='new-owner',fencing_token=fencing_token+1,lease_until=clock_timestamp()+interval '1 minute' WHERE payment_id=?", paymentId);
        when(psp.lookup(anyString())).thenReturn(PspLookupResult.approved(view(paymentId, PspOutcome.APPROVED)));

        service.reconcile(claim);

        assertThat(status(paymentId)).isEqualTo("UNKNOWN");
        assertThat(jobResult(paymentId)).isEqualTo("STALE_WRITE_REJECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reconciliation_audit WHERE payment_id=? AND outcome='STALE_WRITE_REJECTED'", Integer.class, paymentId)).isEqualTo(1);
    }

    @Test
    void unknownPspStatusIsRetriedWithoutInventingAResult() {
        var paymentId = insertUnknown("unknown");
        when(psp.lookup(anyString())).thenReturn(PspLookupResult.unknown("provider still processing"));

        service.runBatch();

        assertThat(status(paymentId)).isEqualTo("UNKNOWN");
        assertThat(jobResult(paymentId)).isEqualTo("STATUS_UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_audit WHERE payment_id=? AND new_status IN ('SUCCEEDED','FAILED')", Integer.class, paymentId)).isZero();
    }

    private ReconciliationService service(String owner) {
        return new ReconciliationService(jdbc, tx, psp, afterLookup, properties,
                new ReconciliationIdentity(owner), json);
    }

    private UUID insertUnknown(String suffix) {
        var paymentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payments(payment_id,tenant_id,request_hash,amount_minor,currency,status,external_reference,
                    merchant_reference,version,result_code)
                VALUES (?,'reconciliation-test','hash',1234,'BRL','UNKNOWN',?, ?,3,'PSP_RESPONSE_LOST')
                """, paymentId, reference(paymentId), "gate06-" + suffix);
        jdbc.update("""
                INSERT INTO payment_audit(payment_id,previous_status,new_status,fencing_token,correlation_id,reason)
                VALUES (?,'SENT_TO_PSP','UNKNOWN',1,?,'PSP_RESPONSE_LOST')
                """, paymentId, "corr-" + suffix);
        return paymentId;
    }

    private PspTransactionView view(UUID paymentId, PspOutcome outcome) {
        return new PspTransactionView(UUID.randomUUID(), paymentId, reference(paymentId), 1234, "BRL",
                outcome == PspOutcome.APPROVED ? SimulationScenario.APPROVED : SimulationScenario.REJECTED,
                outcome, true, Instant.now());
    }

    private String status(UUID paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE payment_id=?", String.class, paymentId);
    }

    private String jobResult(UUID paymentId) {
        return jdbc.queryForObject("SELECT last_result FROM reconciliation_jobs WHERE payment_id=?", String.class, paymentId);
    }

    private int count(String table, UUID paymentId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE payment_id=?", Integer.class, paymentId);
    }

    private static String reference(UUID paymentId) {
        return "pxf-" + paymentId;
    }

    private static final class SimulatedProcessCrash extends RuntimeException { }
}
