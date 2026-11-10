package dev.pixflow.lab.worker;

import dev.pixflow.lab.contracts.PaymentRequestedEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LeaseService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final long ttlMillis;
    private final String groupId;

    public LeaseService(JdbcTemplate jdbc, TransactionTemplate tx,
            @Value("${pixflow.worker.lease-ttl-ms:30000}") long ttlMillis,
            @Value("${pixflow.worker.group-id:pixflow-payment-worker-v1}") String groupId) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.ttlMillis = ttlMillis;
        this.groupId = groupId;
    }

    public Optional<PaymentLease> acquire(UUID paymentId, String ownerId, PaymentRequestedEvent event) {
        PaymentLease acquired = tx.execute(status -> jdbc.query("""
                UPDATE payments SET lease_owner=?, lease_until=clock_timestamp() + (? * interval '1 millisecond'),
                    fencing_token=fencing_token+1, version=version+1, updated_at=clock_timestamp()
                WHERE payment_id=? AND status IN ('PENDING','PROCESSING','SENT_TO_PSP','UNKNOWN')
                  AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                RETURNING payment_id,lease_owner,fencing_token,lease_until,status,external_reference,amount_minor,currency
                """, (rs,n) -> new PaymentLease(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3),
                rs.getTimestamp(4).toInstant(),rs.getString(5),rs.getString(6),rs.getLong(7),rs.getString(8).trim()),
                ownerId,ttlMillis,paymentId).stream().findFirst().map(lease -> {
                    appendEvent(lease,event,"ACQUIRED","lease granted by PostgreSQL clock");
                    return lease;
                }).orElse(null));
        return Optional.ofNullable(acquired);
    }
    public Optional<Instant> renew(PaymentLease lease, PaymentRequestedEvent event) {
        return tx.execute(status -> jdbc.query("""
                UPDATE payments SET lease_until=clock_timestamp() + (? * interval '1 millisecond'), updated_at=clock_timestamp()
                WHERE payment_id=? AND lease_owner=? AND fencing_token=? AND lease_until > clock_timestamp()
                RETURNING lease_until
                """,(rs,n)->rs.getTimestamp(1).toInstant(),ttlMillis,lease.paymentId(),lease.ownerId(),lease.fencingToken())
                .stream().findFirst().map(until -> {
                    var renewed = new PaymentLease(lease.paymentId(),lease.ownerId(),lease.fencingToken(),until,
                            lease.status(),lease.externalReference(),lease.amountMinor(),lease.currency());
                    appendEvent(renewed,event,"RENEWED","lease renewed by current owner and token");
                    return until;
                }));
    }

    public void release(PaymentLease lease, PaymentRequestedEvent event) {
        tx.executeWithoutResult(status -> {
            int count=jdbc.update("""
                    UPDATE payments SET lease_owner=NULL,lease_until=NULL,updated_at=clock_timestamp()
                    WHERE payment_id=? AND lease_owner=? AND fencing_token=? AND lease_until > clock_timestamp()
                    """,lease.paymentId(),lease.ownerId(),lease.fencingToken());
            if (count==1) appendEvent(lease,event,"RELEASED","result durably persisted");
            else recordRejected(lease,event,"lease expired before release");
        });
    }

    public void recordRejected(PaymentLease lease, PaymentRequestedEvent event, String detail) {
        tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO payment_lease_audit(lease_event_id,payment_id,owner_id,fencing_token,event_type,lease_until,event_id,correlation_id,detail)
                VALUES (?,?,?,?,'WRITE_REJECTED',?, ?,?,?)
                """,UUID.randomUUID(),lease.paymentId(),lease.ownerId(),lease.fencingToken(),
                Timestamp.from(lease.leaseUntil()),event.eventId(),event.correlationId(),detail));
    }

    public void defer(PaymentLease attemptedLease, PaymentRequestedEvent event, String payload, String detail) {
        tx.executeWithoutResult(status -> deferInCurrentTransaction(attemptedLease,event,payload,detail));
    }

    public void deferInCurrentTransaction(PaymentLease attemptedLease, PaymentRequestedEvent event, String payload, String detail) {
        var state = jdbc.query("SELECT lease_owner,fencing_token,lease_until FROM payments WHERE payment_id=?",
                (rs,n)->new Object[]{rs.getString(1),rs.getLong(2),rs.getTimestamp(3)},event.paymentId()).stream().findFirst();
        if (state.isEmpty()) throw new IllegalArgumentException("payment does not exist");
        var current = state.get();
        Timestamp leaseUntil = (Timestamp) current[2];
        Timestamp due = jdbc.queryForObject("SELECT COALESCE(lease_until,clock_timestamp()) + interval '250 milliseconds' FROM payments WHERE payment_id=?",Timestamp.class,event.paymentId());
        jdbc.update("""
                INSERT INTO worker_deferred_events(consumer_name,event_id,payment_id,partition_key,payload_json,next_attempt_at,last_error)
                VALUES (?,?,?, ?,?::jsonb,?,?)
                ON CONFLICT (consumer_name,event_id) DO UPDATE SET
                    next_attempt_at=GREATEST(worker_deferred_events.next_attempt_at,EXCLUDED.next_attempt_at),
                    attempt_count=worker_deferred_events.attempt_count+1,last_error=EXCLUDED.last_error,claimed_by=NULL,claim_token=NULL,claimed_until=NULL
                """,groupId,event.eventId(),event.paymentId(),event.paymentId().toString(),payload,due,detail);
        jdbc.update("""
                INSERT INTO payment_lease_audit(lease_event_id,payment_id,owner_id,fencing_token,event_type,lease_until,event_id,correlation_id,detail)
                VALUES (?,?,?,?,'BUSY_DEFERRED',?,?,?,?)
                """,UUID.randomUUID(),event.paymentId(),current[0] == null ? attemptedLease.ownerId() : current[0],
                current[1],leaseUntil,event.eventId(),event.correlationId(),detail);
    }

    private void appendEvent(PaymentLease lease, PaymentRequestedEvent event, String type, String detail) {
        jdbc.update("""
                INSERT INTO payment_lease_audit(lease_event_id,payment_id,owner_id,fencing_token,event_type,lease_until,event_id,correlation_id,detail)
                VALUES (?,?,?,?,?,?, ?,?,?)
                """,UUID.randomUUID(),lease.paymentId(),lease.ownerId(),lease.fencingToken(),type,
                Timestamp.from(lease.leaseUntil()),event.eventId(),event.correlationId(),detail);
    }
}
