package dev.pixflow.lab.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pixflow.lab.contracts.PaymentRequestedEvent;
import dev.pixflow.lab.contracts.PaymentResultEvent;
import dev.pixflow.lab.contracts.PspChargeRequest;
import dev.pixflow.lab.contracts.PspOutcome;
import dev.pixflow.lab.contracts.PspTransactionView;
import dev.pixflow.lab.contracts.SimulationScenario;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class PaymentProcessingService {
    private static final Logger log = LoggerFactory.getLogger(PaymentProcessingService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PspClient psp;
    private final ObjectMapper json;
    private final AfterPspCallHook afterPspCall;
    private final LeaseService leases;
    private final WorkerIdentity identity;
    private final String groupId;
    private final String resultTopic;
    private final MeterRegistry meters;

    public PaymentProcessingService(JdbcTemplate jdbc, TransactionTemplate tx, PspClient psp, ObjectMapper json,
            AfterPspCallHook afterPspCall, LeaseService leases, WorkerIdentity identity,
            @Value("${pixflow.worker.group-id}") String groupId,
            @Value("${pixflow.worker.result-topic}") String resultTopic,
            MeterRegistry meters) {
        this.jdbc=jdbc; this.tx=tx; this.psp=psp; this.json=json; this.afterPspCall=afterPspCall;
        this.leases=leases; this.identity=identity; this.groupId=groupId; this.resultTopic=resultTopic;
        this.meters=meters;
    }

    public void process(PaymentRequestedEvent event) {
        if (event.eventId()==null || event.paymentId()==null || event.amountMinor()<=0 || event.correlationId()==null) {
            throw new IllegalArgumentException("invalid payment.requested event");
        }
        var payload = serialize(event);
        var state = tx.execute(status -> {
            if (jdbc.queryForObject("SELECT count(*) FROM inbox_processed WHERE consumer_name=? AND event_id=?", Integer.class, groupId, event.eventId()) > 0) return null;
            return jdbc.query("SELECT payment_id,external_reference,amount_minor,currency,status FROM payments WHERE payment_id=?",
                    (rs,n)->new PaymentRow(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3),rs.getString(4).trim(),rs.getString(5)),event.paymentId()).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("payment does not exist"));
        });
        if (state == null) return;
        if (state.amountMinor()!=event.amountMinor() || !state.currency().equals(event.currency())) throw new IllegalArgumentException("event does not match payment");
        if (state.status().equals("SUCCEEDED") || state.status().equals("FAILED") || state.status().equals("MANUAL_REVIEW")) {
            tx.executeWithoutResult(status -> jdbc.update("INSERT INTO inbox_processed(consumer_name,event_id) VALUES (?,?) ON CONFLICT DO NOTHING",groupId,event.eventId()));
            return;
        }

        var lease = leases.acquire(event.paymentId(),identity.ownerId(),event).orElse(null);
        if (lease == null) {
            var attempted = new PaymentLease(state.paymentId(),identity.ownerId(),0,Instant.now(),state.status(),state.externalReference(),state.amountMinor(),state.currency());
            leases.defer(attempted,event,payload,"another worker currently owns the payment lease");
            return;
        }
        try {
            var current = lease.status();
            if (current.equals("PENDING")) {
                if (!transition(lease,"PENDING","PROCESSING",event,"WORKER_ACCEPTED")) return;
                current = "PROCESSING";
            }
            if (current.equals("PROCESSING")) {
                if (!transition(lease,"PROCESSING","SENT_TO_PSP",event,"PSP_INTENT_DURABLE")) return;
                current = "SENT_TO_PSP";
            }
            if (!current.equals("SENT_TO_PSP") && !current.equals("UNKNOWN")) {
                leases.recordRejected(lease,event,"payment status is not eligible for PSP processing");
                return;
            }

            PspTransactionView outcome;
            try {
                var existing = psp.find(lease.externalReference());
                if (existing.isPresent()) outcome = existing.get();
                else outcome = psp.charge(new PspChargeRequest(event.paymentId(),lease.externalReference(),lease.amountMinor(),lease.currency(),
                        event.simulationScenario()==null ? SimulationScenario.APPROVED : event.simulationScenario()));
                afterPspCall.afterCall();
            } catch (PspOutcomeUnknownException ambiguous) {
                finalizePayment(event,lease,"UNKNOWN","PSP_RESPONSE_LOST",payload);
                return;
            }
            var finalStatus = outcome.outcome()==PspOutcome.APPROVED ? "SUCCEEDED" : "FAILED";
            finalizePayment(event,lease,finalStatus,outcome.outcome().name(),payload);
        } finally {
            leases.release(lease,event);
        }
    }

    private boolean transition(PaymentLease lease,String expected,String next,PaymentRequestedEvent event,String reason) {
        var updated=tx.execute(status -> {
            int count=jdbc.update("""
                    UPDATE payments SET status=?,version=version+1,updated_at=clock_timestamp(),
                        sent_to_psp_at=CASE WHEN ?='SENT_TO_PSP' THEN clock_timestamp() ELSE sent_to_psp_at END
                    WHERE payment_id=? AND status=? AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp()
                    """,next,next,lease.paymentId(),expected,lease.ownerId(),lease.fencingToken());
            if (count==1) {
                audit(lease,event,expected,next,reason);
                return true;
            }
            leases.recordRejected(lease,event,"stale or expired fencing token while transitioning "+expected+" to "+next);
            return false;
        });
        if (!Boolean.TRUE.equals(updated)) deferCurrent(lease,event,serialize(event),"lease lost before state transition");
        return Boolean.TRUE.equals(updated);
    }

    private void finalizePayment(PaymentRequestedEvent event,PaymentLease lease,String finalStatus,String code,String payload) {
        var completed=tx.execute(status -> {
            var previous=jdbc.query("SELECT status FROM payments WHERE payment_id=?",(rs,n)->rs.getString(1),lease.paymentId())
                    .stream().findFirst().orElse("UNKNOWN");
            if (!previous.equals("SENT_TO_PSP") && !previous.equals("UNKNOWN")) {
                leases.recordRejected(lease,event,"payment state no longer accepts the PSP result");
                return false;
            }
            int updated=jdbc.update("""
                    UPDATE payments SET status=?,result_code=?,version=version+1,updated_at=clock_timestamp()
                    WHERE payment_id=? AND status=? AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp()
                    """,finalStatus,code,lease.paymentId(),previous,lease.ownerId(),lease.fencingToken());
            if (updated!=1) {
                leases.recordRejected(lease,event,"stale or expired fencing token while persisting PSP result");
                return false;
            }
            audit(lease,event,previous,finalStatus,"PSP_"+code);
            insertResultOutbox(event,finalStatus,code);
            jdbc.update("INSERT INTO inbox_processed(consumer_name,event_id) VALUES (?,?) ON CONFLICT DO NOTHING",groupId,event.eventId());
            return true;
        });
        if (Boolean.TRUE.equals(completed)) {
            meters.counter("pixflow.payment.results", "outcome", finalStatus.toLowerCase()).increment();
            recordResolution(lease.paymentId(), finalStatus.toLowerCase());
            log.atInfo().addKeyValue("operation", "payment_process").addKeyValue("outcome", finalStatus.toLowerCase())
                    .log("payment result persisted");
        }
        if (!Boolean.TRUE.equals(completed) && jdbc.queryForObject("SELECT count(*) FROM inbox_processed WHERE consumer_name=? AND event_id=?",Integer.class,groupId,event.eventId())==0) deferCurrent(lease,event,payload,"lease lost before persisting PSP result");
    }

    private void deferCurrent(PaymentLease lease,PaymentRequestedEvent event,String payload,String detail) {
        leases.defer(lease,event,payload,detail);
    }

    private void insertResultOutbox(PaymentRequestedEvent event,String state,String code) {
        var result=new PaymentResultEvent(UUID.randomUUID(),event.paymentId(),"payment.result",1,Instant.now(),event.correlationId(),state,code);
        try {
            var payload=json.writeValueAsString(result);
            jdbc.update("INSERT INTO outbox_events(event_id,aggregate_id,aggregate_version,topic,event_type,payload_json,partition_key) SELECT ?,payment_id,version,?,'payment.result',?::jsonb,payment_id::text FROM payments WHERE payment_id=?",
                    result.eventId(),resultTopic,payload,event.paymentId());
        } catch (JsonProcessingException e) { throw new IllegalStateException("could not serialize payment result",e); }
    }

    private void audit(PaymentLease lease,PaymentRequestedEvent event,String previous,String next,String reason) {
        jdbc.update("INSERT INTO payment_audit(payment_id,previous_status,new_status,fencing_token,event_id,correlation_id,reason) VALUES (?,?,?,?,?,?,?)",
                lease.paymentId(),previous,next,lease.fencingToken(),event.eventId(),event.correlationId(),reason);
    }

    private String serialize(PaymentRequestedEvent event) {
        try { return json.writeValueAsString(event); }
        catch (JsonProcessingException e) { throw new IllegalStateException("could not serialize deferred event",e); }
    }

    private void recordResolution(UUID paymentId, String outcome) {
        var millis = jdbc.queryForObject("SELECT GREATEST(0,EXTRACT(EPOCH FROM (clock_timestamp()-created_at))*1000)::bigint FROM payments WHERE payment_id=?", Long.class, paymentId);
        if (millis != null) Timer.builder("pixflow.payment.resolution").tag("outcome", outcome)
                .register(meters).record(Duration.ofMillis(millis));
    }
    private record PaymentRow(UUID paymentId,String externalReference,long amountMinor,String currency,String status) {}
}
