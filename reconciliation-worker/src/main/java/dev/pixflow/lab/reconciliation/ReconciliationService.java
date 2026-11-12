package dev.pixflow.lab.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pixflow.lab.contracts.PaymentResultEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@Service
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PspLookupClient psp;
    private final AfterPspLookupHook afterLookup;
    private final ReconciliationProperties properties;
    private final ReconciliationIdentity identity;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    public ReconciliationService(JdbcTemplate jdbc, TransactionTemplate tx, PspLookupClient psp,
            AfterPspLookupHook afterLookup, ReconciliationProperties properties,
            ReconciliationIdentity identity, ObjectMapper json, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.psp = psp;
        this.afterLookup = afterLookup;
        this.properties = properties;
        this.identity = identity;
        this.json = json;
        this.meters = meters;
    }

    public int discoverUnknownPayments() {
        return jdbc.update("""
                INSERT INTO reconciliation_jobs(payment_id,correlation_id,max_attempts)
                SELECT p.payment_id,
                       COALESCE((SELECT a.correlation_id FROM payment_audit a
                                 WHERE a.payment_id=p.payment_id ORDER BY a.occurred_at DESC,a.audit_id DESC LIMIT 1),
                                'recon-' || p.payment_id::text),
                       ?
                  FROM payments p
                 WHERE p.status='UNKNOWN'
                ON CONFLICT (payment_id) DO NOTHING
                """, properties.maxAttempts());
    }

    public Optional<ReconciliationClaim> claimNext() {
        return Optional.ofNullable(tx.execute(status -> {
            var candidate = jdbc.query("""
                    SELECT j.payment_id,p.external_reference,j.attempt_count,j.max_attempts,j.correlation_id
                      FROM reconciliation_jobs j
                      JOIN payments p ON p.payment_id=j.payment_id
                     WHERE j.completed_at IS NULL
                       AND j.attempt_count < j.max_attempts
                       AND j.next_attempt_at <= clock_timestamp()
                       AND (j.claimed_until IS NULL OR j.claimed_until <= clock_timestamp())
                       AND p.status='UNKNOWN'
                       AND (p.lease_until IS NULL OR p.lease_until <= clock_timestamp())
                     ORDER BY j.next_attempt_at,j.created_at,j.payment_id
                     LIMIT 1
                     FOR UPDATE OF j,p SKIP LOCKED
                    """, (rs, row) -> new Candidate(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                    rs.getInt(4), rs.getString(5))).stream().findFirst().orElse(null);
            if (candidate == null) return null;

            var claimToken = UUID.randomUUID();
            var lease = jdbc.query("""
                    UPDATE payments
                       SET lease_owner=?,lease_until=clock_timestamp() + (? * interval '1 millisecond'),
                           fencing_token=fencing_token+1,version=version+1,updated_at=clock_timestamp()
                     WHERE payment_id=? AND status='UNKNOWN'
                       AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                    RETURNING fencing_token,lease_until
                    """, (rs, row) -> new LeaseResult(rs.getLong(1), rs.getTimestamp(2).toInstant()),
                    identity.ownerId(), properties.leaseTtl().toMillis(), candidate.paymentId()).stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("payment lease was lost while claiming reconciliation"));

            var attempt = candidate.attemptCount() + 1;
            int claimed = jdbc.update("""
                    UPDATE reconciliation_jobs
                       SET claimed_by=?,claim_token=?,claimed_until=?,attempt_count=?,
                           last_attempt_at=clock_timestamp(),updated_at=clock_timestamp()
                     WHERE payment_id=? AND completed_at IS NULL
                    """, identity.ownerId(), claimToken, Timestamp.from(lease.leaseUntil()), attempt,
                    candidate.paymentId());
            if (claimed != 1) throw new IllegalStateException("reconciliation job claim was lost");

            var claim = new ReconciliationClaim(candidate.paymentId(), candidate.externalReference(),
                    identity.ownerId(), claimToken, lease.fencingToken(), lease.leaseUntil(), attempt,
                    candidate.maxAttempts(), candidate.correlationId());
            audit(claim, "CLAIMED", null, "work claimed with PostgreSQL lease");
            return claim;
        }));
    }

    public boolean reconcileNext() {
        var claim = claimNext().orElse(null);
        if (claim == null) return false;
        reconcile(claim);
        return true;
    }

    public int runBatch() {
        discoverUnknownPayments();
        int processed = 0;
        while (processed < properties.batchSize() && reconcileNext()) processed++;
        return processed;
    }

    public void reconcile(ReconciliationClaim claim) {
        var sample = Timer.start(meters);
        var metricOutcome = "temporary_failure";
        MDC.put("correlationId", claim.correlationId());
        MDC.put("paymentId", claim.paymentId().toString());
        final PspLookupResult result;
        try {
            try {
                result = psp.lookup(claim.externalReference());
            } catch (RuntimeException failure) {
                retry(claim, PspLookupResult.temporaryFailure(failure.getClass().getSimpleName() + ": " + failure.getMessage()));
                return;
            }
            metricOutcome = result.status().name().toLowerCase();
            afterLookup.afterLookup(claim, result);
            switch (result.status()) {
                case APPROVED -> complete(claim, result, "SUCCEEDED", "RECONCILED_APPROVED", "CONFIRMED_APPROVED");
                case REJECTED -> complete(claim, result, "FAILED", "RECONCILED_REJECTED", "CONFIRMED_REJECTED");
                case UNKNOWN, NOT_FOUND, TEMPORARY_FAILURE -> retry(claim, result);
            }
        } finally {
            meters.counter("pixflow.reconciliation.attempts", "outcome", metricOutcome).increment();
            sample.stop(Timer.builder("pixflow.reconciliation.duration").tag("outcome", metricOutcome).register(meters));
            MDC.clear();
        }
    }

    private void complete(ReconciliationClaim claim, PspLookupResult result, String finalStatus,
            String resultCode, String auditOutcome) {
        tx.executeWithoutResult(status -> {
            if (!ownsJob(claim)) return;
            var current = paymentStatus(claim.paymentId());
            if (isTerminal(current)) {
                finishPreservingTerminal(claim, current);
                return;
            }
            if (!"UNKNOWN".equals(current)) {
                stale(claim, "payment no longer has UNKNOWN status");
                return;
            }
            if (result.transaction() == null
                    || !claim.paymentId().equals(result.transaction().paymentId())
                    || !claim.externalReference().equals(result.transaction().externalReference())) {
                scheduleRetry(claim, "STATUS_UNKNOWN", "PSP response does not match payment identity", null);
                return;
            }

            var event = resultEvent(claim, finalStatus, resultCode);
            int updated = jdbc.update("""
                    UPDATE payments
                       SET status=?,result_code=?,version=version+1,updated_at=clock_timestamp(),
                           lease_owner=NULL,lease_until=NULL
                     WHERE payment_id=? AND status='UNKNOWN' AND lease_owner=? AND fencing_token=?
                       AND lease_until>clock_timestamp()
                    """, finalStatus, resultCode, claim.paymentId(), claim.ownerId(), claim.fencingToken());
            if (updated != 1) {
                stale(claim, "stale or expired fencing token while persisting reconciliation result");
                return;
            }
            insertResultOutbox(claim, event);
            jdbc.update("""
                    INSERT INTO payment_audit(payment_id,previous_status,new_status,fencing_token,event_id,correlation_id,reason)
                    VALUES (?,'UNKNOWN',?,?,?,?,?)
                    """, claim.paymentId(), finalStatus, claim.fencingToken(), event.eventId(),
                    claim.correlationId(), resultCode);
            jdbc.update("""
                    UPDATE reconciliation_jobs
                       SET completed_at=clock_timestamp(),last_result=?,last_error=NULL,
                           claimed_by=NULL,claim_token=NULL,claimed_until=NULL,updated_at=clock_timestamp()
                     WHERE payment_id=? AND claimed_by=? AND claim_token=?
                    """, auditOutcome, claim.paymentId(), claim.ownerId(), claim.claimToken());
            audit(claim, auditOutcome, result.transaction().transactionId(), result.detail());
            meters.counter("pixflow.reconciliation.results", "outcome", finalStatus.toLowerCase()).increment();
            recordResolution(claim.paymentId(), finalStatus.toLowerCase());
        });
    }

    private void retry(ReconciliationClaim claim, PspLookupResult result) {
        tx.executeWithoutResult(status -> {
            if (!ownsJob(claim)) return;
            var current = paymentStatus(claim.paymentId());
            if (isTerminal(current)) {
                finishPreservingTerminal(claim, current);
                return;
            }
            if (!"UNKNOWN".equals(current)) {
                stale(claim, "payment no longer has UNKNOWN status");
                return;
            }
            if (claim.attemptNumber() >= claim.maxAttempts()) {
                moveToManualReview(claim, result);
                return;
            }
            var auditOutcome = result.status() == PspLookupResult.Status.UNKNOWN
                    ? "STATUS_UNKNOWN" : result.status().name();
            scheduleRetry(claim, auditOutcome, result.detail(), transactionId(result));
        });
    }

    private void scheduleRetry(ReconciliationClaim claim, String outcome, String detail, UUID transactionId) {
        long delay = retryDelayMillis(claim.attemptNumber());
        int released = jdbc.update("""
                UPDATE payments SET lease_owner=NULL,lease_until=NULL,updated_at=clock_timestamp()
                 WHERE payment_id=? AND status='UNKNOWN' AND lease_owner=? AND fencing_token=?
                """, claim.paymentId(), claim.ownerId(), claim.fencingToken());
        if (released != 1) {
            stale(claim, "stale fencing token while scheduling reconciliation retry");
            return;
        }
        jdbc.update("""
                UPDATE reconciliation_jobs
                   SET next_attempt_at=clock_timestamp() + (? * interval '1 millisecond'),
                       last_result=?,last_error=?,claimed_by=NULL,claim_token=NULL,claimed_until=NULL,
                       updated_at=clock_timestamp()
                 WHERE payment_id=? AND claimed_by=? AND claim_token=?
                """, delay, outcome, sanitize(detail), claim.paymentId(), claim.ownerId(), claim.claimToken());
        audit(claim, outcome, transactionId, detail + "; retry in " + delay + " ms");
    }

    private void moveToManualReview(ReconciliationClaim claim, PspLookupResult result) {
        var event = resultEvent(claim, "MANUAL_REVIEW", "RECONCILIATION_EXHAUSTED");
        int updated = jdbc.update("""
                UPDATE payments
                   SET status='MANUAL_REVIEW',result_code='RECONCILIATION_EXHAUSTED',
                       version=version+1,updated_at=clock_timestamp(),lease_owner=NULL,lease_until=NULL
                 WHERE payment_id=? AND status='UNKNOWN' AND lease_owner=? AND fencing_token=?
                   AND lease_until>clock_timestamp()
                """, claim.paymentId(), claim.ownerId(), claim.fencingToken());
        if (updated != 1) {
            stale(claim, "stale or expired fencing token while moving to manual review");
            return;
        }
        insertResultOutbox(claim, event);
        jdbc.update("""
                INSERT INTO payment_audit(payment_id,previous_status,new_status,fencing_token,event_id,correlation_id,reason)
                VALUES (?,'UNKNOWN','MANUAL_REVIEW',?,?,?,'RECONCILIATION_MAX_ATTEMPTS')
                """, claim.paymentId(), claim.fencingToken(), event.eventId(), claim.correlationId());
        jdbc.update("""
                UPDATE reconciliation_jobs
                   SET completed_at=clock_timestamp(),last_result='MANUAL_REVIEW',last_error=?,
                       claimed_by=NULL,claim_token=NULL,claimed_until=NULL,updated_at=clock_timestamp()
                 WHERE payment_id=? AND claimed_by=? AND claim_token=?
                """, sanitize(result.detail()), claim.paymentId(), claim.ownerId(), claim.claimToken());
        audit(claim, "MANUAL_REVIEW", transactionId(result), "attempt limit reached: " + result.detail());
        meters.counter("pixflow.reconciliation.results", "outcome", "manual_review").increment();
        recordResolution(claim.paymentId(), "manual_review");
    }

    private void finishPreservingTerminal(ReconciliationClaim claim, String current) {
        jdbc.update("""
                UPDATE reconciliation_jobs
                   SET completed_at=clock_timestamp(),last_result='TERMINAL_PRESERVED',last_error=NULL,
                       claimed_by=NULL,claim_token=NULL,claimed_until=NULL,updated_at=clock_timestamp()
                 WHERE payment_id=? AND claimed_by=? AND claim_token=?
                """, claim.paymentId(), claim.ownerId(), claim.claimToken());
        jdbc.update("""
                UPDATE payments SET lease_owner=NULL,lease_until=NULL,updated_at=clock_timestamp()
                 WHERE payment_id=? AND lease_owner=? AND fencing_token=?
                """, claim.paymentId(), claim.ownerId(), claim.fencingToken());
        audit(claim, "TERMINAL_PRESERVED", null, "terminal status preserved: " + current);
    }

    private void stale(ReconciliationClaim claim, String detail) {
        meters.counter("pixflow.fencing.rejections", "component", "reconciliation").increment();
        jdbc.update("""
                UPDATE reconciliation_jobs
                   SET next_attempt_at=clock_timestamp(),last_result='STALE_WRITE_REJECTED',last_error=?,
                       claimed_by=NULL,claim_token=NULL,claimed_until=NULL,updated_at=clock_timestamp()
                 WHERE payment_id=? AND claimed_by=? AND claim_token=?
                """, sanitize(detail), claim.paymentId(), claim.ownerId(), claim.claimToken());
        audit(claim, "STALE_WRITE_REJECTED", null, detail);
    }

    private boolean ownsJob(ReconciliationClaim claim) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM reconciliation_jobs
                 WHERE payment_id=? AND claimed_by=? AND claim_token=? AND completed_at IS NULL
                """, Integer.class, claim.paymentId(), claim.ownerId(), claim.claimToken()) == 1;
    }

    private String paymentStatus(UUID paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE payment_id=?", String.class, paymentId);
    }

    private void audit(ReconciliationClaim claim, String outcome, UUID transactionId, String detail) {
        jdbc.update("""
                INSERT INTO reconciliation_audit(payment_id,attempt_number,owner_id,fencing_token,correlation_id,
                    external_reference,outcome,psp_transaction_id,detail)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, claim.paymentId(), claim.attemptNumber(), claim.ownerId(), claim.fencingToken(),
                claim.correlationId(), claim.externalReference(), outcome, transactionId, sanitize(detail));
        log.atInfo().addKeyValue("operation", "reconciliation").addKeyValue("outcome", outcome.toLowerCase())
                .addKeyValue("attempt", claim.attemptNumber()).addKeyValue("fencingToken", claim.fencingToken())
                .log("reconciliation audit persisted");
    }

    private PaymentResultEvent resultEvent(ReconciliationClaim claim, String state, String code) {
        return new PaymentResultEvent(UUID.randomUUID(), claim.paymentId(), "payment.result", 1,
                Instant.now(), claim.correlationId(), state, code);
    }

    private void insertResultOutbox(ReconciliationClaim claim, PaymentResultEvent event) {
        try {
            var payload = json.writeValueAsString(event);
            jdbc.update("""
                    INSERT INTO outbox_events(event_id,aggregate_id,aggregate_version,topic,event_type,payload_json,partition_key)
                    SELECT ?,payment_id,version,?,'payment.result',?::jsonb,payment_id::text
                      FROM payments WHERE payment_id=?
                    """, event.eventId(), properties.resultTopic(), payload, claim.paymentId());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize reconciliation result", exception);
        }
    }

    private long retryDelayMillis(int attempt) {
        long factor = 1L << Math.min(Math.max(attempt - 1, 0), 20);
        long exponential;
        try { exponential = Math.multiplyExact(properties.baseBackoff().toMillis(), factor); }
        catch (ArithmeticException overflow) { exponential = Long.MAX_VALUE; }
        long capped = Math.min(exponential, properties.maxBackoff().toMillis());
        long jitterBound = properties.jitter().toMillis();
        return capped + (jitterBound <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterBound + 1));
    }

    private static UUID transactionId(PspLookupResult result) {
        return result.transaction() == null ? null : result.transaction().transactionId();
    }

    private static boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "MANUAL_REVIEW".equals(status);
    }

    private static String sanitize(String value) {
        if (value == null) return null;
        var clean = value.replaceAll("[\\r\\n\\t]+", " ");
        return clean.length() <= 500 ? clean : clean.substring(0, 500);
    }

    private void recordResolution(UUID paymentId, String outcome) {
        var millis = jdbc.queryForObject("SELECT GREATEST(0,EXTRACT(EPOCH FROM (clock_timestamp()-created_at))*1000)::bigint FROM payments WHERE payment_id=?", Long.class, paymentId);
        if (millis != null) Timer.builder("pixflow.payment.resolution").tag("outcome", outcome)
                .register(meters).record(Duration.ofMillis(millis));
    }

    private record Candidate(UUID paymentId, String externalReference, int attemptCount, int maxAttempts,
            String correlationId) { }
    private record LeaseResult(long fencingToken, Instant leaseUntil) { }
}
