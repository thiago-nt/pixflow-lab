package dev.pixflow.lab.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pixflow.lab.contracts.PaymentRequestedEvent;
import dev.pixflow.lab.contracts.PspChargeRequest;
import dev.pixflow.lab.contracts.PspOutcome;
import dev.pixflow.lab.contracts.PspTransactionView;
import dev.pixflow.lab.contracts.SimulationScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.transaction.support.TransactionTemplate;

@JdbcTest
@Import({PaymentProcessingService.class, PaymentProcessingIntegrationTest.Config.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentProcessingIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine")
            .withDatabaseName("pixflow").withUsername("pixflow_app").withPassword("test-only");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
    }
    @BeforeAll static void schema() throws Exception {
        try (var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())) {
            for (var migration: List.of("V001__create_payment_core.sql","V002__payment_api_pending_state.sql","V003__outbox_claim_ownership.sql","V004__worker_and_psp_simulator.sql")) {
                var path=Path.of(System.getProperty("user.dir"),"infra","postgres","migrations",migration);
                if (!Files.exists(path)) path=Path.of(System.getProperty("user.dir"),"..","infra","postgres","migrations",migration);
                ScriptUtils.executeSqlScript(connection,new FileSystemResource(path));
            }
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentProcessingService processing;
    @MockitoBean PspClient psp;
    @MockitoBean AfterPspCallHook afterPspCallHook;
    private UUID paymentId;
    private PaymentRequestedEvent event;

    @BeforeEach void setUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_payment_success_test ON payments");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_payment_success_test()");
        jdbc.execute("TRUNCATE payment_audit,outbox_events,inbox_processed,idempotency_keys,payments RESTART IDENTITY CASCADE");
        paymentId=UUID.randomUUID();
        jdbc.update("INSERT INTO payments(payment_id,tenant_id,request_hash,amount_minor,currency,status,external_reference,merchant_reference) VALUES (?, 'acct-test','hash',1234,'BRL','PENDING',?, 'synthetic')",paymentId,"pxf-"+paymentId);
        event=new PaymentRequestedEvent(UUID.randomUUID(),paymentId,"payment.requested",1,Instant.now(),"corr-test",1234,"BRL",SimulationScenario.APPROVED);
    }

    @Test void persistsResultAuditOutboxAndInboxBeforeCallerCanAcknowledge() {
        when(psp.find("pxf-"+paymentId)).thenReturn(java.util.Optional.empty());
        when(psp.charge(org.mockito.ArgumentMatchers.any(PspChargeRequest.class))).thenReturn(view(PspOutcome.APPROVED));
        processing.process(event);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE payment_id=?",String.class,paymentId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_processed WHERE consumer_name='pixflow-payment-worker-v1' AND event_id=?",Integer.class,event.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND topic='payment.result.v1'",Integer.class,paymentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_audit WHERE payment_id=? AND new_status IN ('PROCESSING','SENT_TO_PSP','SUCCEEDED')",Integer.class,paymentId)).isEqualTo(3);
        processing.process(event);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND topic='payment.result.v1'",Integer.class,paymentId)).isEqualTo(1);
        org.mockito.Mockito.verify(psp,org.mockito.Mockito.times(1)).charge(org.mockito.ArgumentMatchers.any(PspChargeRequest.class));
    }

    @Test void rejectionIsPersistedAsFailed() {
        when(psp.find("pxf-"+paymentId)).thenReturn(java.util.Optional.empty());
        when(psp.charge(org.mockito.ArgumentMatchers.any(PspChargeRequest.class))).thenReturn(view(PspOutcome.REJECTED));
        processing.process(event);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE payment_id=?",String.class,paymentId)).isEqualTo("FAILED");
    }

    @Test void crashAfterPspEffectCanResumeByStableReferenceWithoutSecondCharge() {
        var pspView=view(PspOutcome.APPROVED);
        when(psp.find("pxf-"+paymentId)).thenReturn(java.util.Optional.empty(),java.util.Optional.of(pspView));
        when(psp.charge(org.mockito.ArgumentMatchers.any(PspChargeRequest.class))).thenReturn(pspView);
        doThrow(new IllegalStateException("simulated crash after PSP response")).doNothing().when(afterPspCallHook).afterCall();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->processing.process(event)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE payment_id=?",String.class,paymentId)).isEqualTo("SENT_TO_PSP");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_processed WHERE event_id=?",Integer.class,event.eventId())).isZero();
        processing.process(event);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE payment_id=?",String.class,paymentId)).isEqualTo("SUCCEEDED");
        org.mockito.Mockito.verify(psp,org.mockito.Mockito.times(1)).charge(org.mockito.ArgumentMatchers.any(PspChargeRequest.class));
    }

    @Test void databaseFailureRollsBackResultAndRedeliveryResolvesBySamePspReference() {
        var pspView=view(PspOutcome.APPROVED);
        when(psp.find("pxf-"+paymentId)).thenReturn(java.util.Optional.empty(),java.util.Optional.of(pspView));
        when(psp.charge(org.mockito.ArgumentMatchers.any(PspChargeRequest.class))).thenReturn(pspView);
        jdbc.execute("CREATE FUNCTION fail_payment_success_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='SUCCEEDED' THEN RAISE EXCEPTION 'simulated result write failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_payment_success_test BEFORE UPDATE ON payments FOR EACH ROW EXECUTE FUNCTION fail_payment_success_test()");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->processing.process(event)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE payment_id=?",String.class,paymentId)).isEqualTo("SENT_TO_PSP");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_processed WHERE event_id=?",Integer.class,event.eventId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id=? AND topic='payment.result.v1'",Integer.class,paymentId)).isZero();
        jdbc.execute("DROP TRIGGER fail_payment_success_test ON payments");
        jdbc.execute("DROP FUNCTION fail_payment_success_test()");
        processing.process(event);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE payment_id=?",String.class,paymentId)).isEqualTo("SUCCEEDED");
        org.mockito.Mockito.verify(psp,org.mockito.Mockito.times(1)).charge(org.mockito.ArgumentMatchers.any(PspChargeRequest.class));
    }

    private PspTransactionView view(PspOutcome outcome) {
        return new PspTransactionView(UUID.randomUUID(),paymentId,"pxf-"+paymentId,1234,"BRL",SimulationScenario.APPROVED,outcome,false,Instant.now());
    }

    @TestConfiguration static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean AfterPspCallHook afterPspCallHook() { return () -> {}; }
        @Bean TransactionTemplate transactionTemplate(org.springframework.transaction.PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
    }
}
