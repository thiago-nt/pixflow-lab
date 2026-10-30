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

@Service
public class PaymentProcessingService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PspClient psp;
    private final ObjectMapper json;
    private final AfterPspCallHook afterPspCall;
    private final String groupId;
    private final String resultTopic;

    public PaymentProcessingService(JdbcTemplate jdbc, TransactionTemplate tx, PspClient psp, ObjectMapper json,
            AfterPspCallHook afterPspCall, @Value("${pixflow.worker.group-id}") String groupId,
            @Value("${pixflow.worker.result-topic}") String resultTopic) {
        this.jdbc=jdbc; this.tx=tx; this.psp=psp; this.json=json; this.afterPspCall=afterPspCall;
        this.groupId=groupId; this.resultTopic=resultTopic;
    }

    public void process(PaymentRequestedEvent event) {
        if (event.eventId()==null || event.paymentId()==null || event.amountMinor()<=0 || event.correlationId()==null) {
            throw new IllegalArgumentException("invalid payment.requested event");
        }
        var row = tx.execute(status -> {
            if (jdbc.queryForObject("SELECT count(*) FROM inbox_processed WHERE consumer_name=? AND event_id=?", Integer.class, groupId, event.eventId()) > 0) return null;
            var rows = jdbc.query("SELECT payment_id,external_reference,amount_minor,currency,status FROM payments WHERE payment_id=? FOR UPDATE",
                    (rs,n)->new PaymentRow(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3),rs.getString(4).trim(),rs.getString(5)),event.paymentId());
            if (rows.isEmpty()) throw new IllegalArgumentException("payment does not exist");
            var payment = rows.getFirst();
            if (payment.amountMinor()!=event.amountMinor() || !payment.currency().equals(event.currency())) throw new IllegalArgumentException("event does not match payment");
            var current = payment;
            if (current.status().equals("PENDING")) {
                transition(current, "PROCESSING", event, "WORKER_ACCEPTED");
                current = new PaymentRow(current.paymentId(),current.externalReference(),current.amountMinor(),current.currency(),"PROCESSING");
            }
            if (current.status().equals("PROCESSING")) {
                transition(current, "SENT_TO_PSP", event, "PSP_INTENT_DURABLE");
                current = new PaymentRow(current.paymentId(),current.externalReference(),current.amountMinor(),current.currency(),"SENT_TO_PSP");
            }
            return current;
        });
        if (row == null) return;

        PspTransactionView outcome;
        try {
            var existing = psp.find(row.externalReference());
            if (existing.isPresent()) outcome = existing.get();
            else {
                outcome = psp.charge(new PspChargeRequest(event.paymentId(), row.externalReference(), row.amountMinor(), row.currency(),
                        event.simulationScenario()==null ? SimulationScenario.APPROVED : event.simulationScenario()));
            }
            afterPspCall.afterCall();
        } catch (PspOutcomeUnknownException ambiguous) {
            finalizePayment(event, row, "UNKNOWN", "PSP_RESPONSE_LOST");
            return;
        }
        var finalStatus = outcome.outcome()==PspOutcome.APPROVED ? "SUCCEEDED" : "FAILED";
        finalizePayment(event, row, finalStatus, outcome.outcome().name());
    }

    private void transition(PaymentRow payment, String next, PaymentRequestedEvent event, String reason) {
        var updated=jdbc.update("UPDATE payments SET status=?,version=version+1,updated_at=clock_timestamp(),sent_to_psp_at=CASE WHEN ?='SENT_TO_PSP' THEN clock_timestamp() ELSE sent_to_psp_at END WHERE payment_id=? AND status=?",
                next,next,payment.paymentId(),payment.status());
        if (updated!=1) throw new IllegalStateException("payment status changed concurrently");
        audit(payment.paymentId(),payment.status(),next,event,reason);
    }

    private void finalizePayment(PaymentRequestedEvent event, PaymentRow initial, String finalStatus, String code) {
        tx.executeWithoutResult(status -> {
            var current=jdbc.queryForObject("SELECT status FROM payments WHERE payment_id=? FOR UPDATE",String.class,initial.paymentId());
            if (current.equals("SUCCEEDED") || current.equals("FAILED") || current.equals("UNKNOWN")) {
                if (!current.equals(finalStatus)) throw new IllegalStateException("terminal payment result conflict");
            } else {
                var updated=jdbc.update("UPDATE payments SET status=?,result_code=?,version=version+1,updated_at=clock_timestamp() WHERE payment_id=? AND status='SENT_TO_PSP'",
                        finalStatus,code,initial.paymentId());
                if (updated!=1) throw new IllegalStateException("durable payment transition failed");
                audit(initial.paymentId(),current,finalStatus,event,"PSP_"+code);
                insertResultOutbox(event, finalStatus, code);
            }
            jdbc.update("INSERT INTO inbox_processed(consumer_name,event_id) VALUES (?,?) ON CONFLICT DO NOTHING",groupId,event.eventId());
        });
    }

    private void insertResultOutbox(PaymentRequestedEvent event, String state, String code) {
        var result = new PaymentResultEvent(UUID.randomUUID(), event.paymentId(), "payment.result", 1, Instant.now(), event.correlationId(), state, code);
        try {
            var payload=json.writeValueAsString(result);
            jdbc.update("INSERT INTO outbox_events(event_id,aggregate_id,aggregate_version,topic,event_type,payload_json,partition_key) SELECT ?,payment_id,version,'payment.result.v1','payment.result',?::jsonb,payment_id::text FROM payments WHERE payment_id=?",
                    result.eventId(),payload,event.paymentId());
        } catch (JsonProcessingException e) { throw new IllegalStateException("could not serialize payment result",e); }
    }

    private void audit(UUID paymentId,String previous,String next,PaymentRequestedEvent event,String reason) {
        jdbc.update("INSERT INTO payment_audit(payment_id,previous_status,new_status,fencing_token,event_id,correlation_id,reason) VALUES (?,?,?,0,?,?,?)",
                paymentId,previous,next,event.eventId(),event.correlationId(),reason);
    }
    private record PaymentRow(UUID paymentId,String externalReference,long amountMinor,String currency,String status) {}
}
