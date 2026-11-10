package dev.pixflow.lab.worker;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DeferredEventPublisher {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final KafkaTemplate<String,String> kafka;
    private final WorkerIdentity identity;
    private final String topic;
    private final long claimMillis;

    public DeferredEventPublisher(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String,String> kafka,
            WorkerIdentity identity, @Value("${pixflow.worker.topic:payment.requested.v1}") String topic,
            @Value("${pixflow.worker.deferred-claim-ms:20000}") long claimMillis) {
        this.jdbc=jdbc; this.tx=tx; this.kafka=kafka; this.identity=identity; this.topic=topic; this.claimMillis=claimMillis;
    }

    @Scheduled(fixedDelayString="${pixflow.worker.deferred-poll-ms:1000}")
    public void publishOne() {
        var claimed = tx.execute(status -> jdbc.query("""
                WITH candidate AS (
                    SELECT consumer_name,event_id FROM worker_deferred_events
                    WHERE next_attempt_at<=clock_timestamp()
                      AND (claimed_until IS NULL OR claimed_until<=clock_timestamp())
                    ORDER BY next_attempt_at,created_at LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE worker_deferred_events d
                SET claimed_by=?,claim_token=?,claimed_until=clock_timestamp() + (? * interval '1 millisecond')
                FROM candidate c
                WHERE d.consumer_name=c.consumer_name AND d.event_id=c.event_id
                RETURNING d.consumer_name,d.event_id,d.partition_key,d.payload_json::text,d.claim_token
                """,(rs,n)->new DeferredEvent(rs.getString(1),rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),rs.getObject(5,UUID.class)),
                identity.ownerId(),UUID.randomUUID(),claimMillis).stream().findFirst().orElse(null));
        if (claimed==null) return;
        try {
            kafka.send(new ProducerRecord<>(topic,claimed.partitionKey(),claimed.payload())).get(10,TimeUnit.SECONDS);
            tx.executeWithoutResult(status -> jdbc.update("DELETE FROM worker_deferred_events WHERE consumer_name=? AND event_id=? AND claimed_by=? AND claim_token=?",
                    claimed.consumerName(),claimed.eventId(),identity.ownerId(),claimed.claimToken()));
        } catch (Exception failure) {
            tx.executeWithoutResult(status -> jdbc.update("""
                    UPDATE worker_deferred_events SET claimed_by=NULL,claim_token=NULL,claimed_until=NULL,
                        next_attempt_at=clock_timestamp() + (LEAST(300, power(2, LEAST(attempt_count,8))) * interval '1 second'),
                        attempt_count=attempt_count+1,last_error=?
                    WHERE consumer_name=? AND event_id=? AND claimed_by=? AND claim_token=?
                    """,failure.getClass().getSimpleName(),claimed.consumerName(),claimed.eventId(),identity.ownerId(),claimed.claimToken()));
        }
    }

    private record DeferredEvent(String consumerName,UUID eventId,String partitionKey,String payload,UUID claimToken) {}
}
