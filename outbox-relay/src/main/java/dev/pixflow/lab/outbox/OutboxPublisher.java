package dev.pixflow.lab.outbox;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final RelayProperties properties;
    private final AfterKafkaAckHook afterAck;
    private final Counter published;
    private final Counter failures;
    private final Counter staleAcks;

    public OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafka,
            RelayProperties properties, AfterKafkaAckHook afterAck, MeterRegistry meters) {
        this.repository = repository;
        this.kafka = kafka;
        this.properties = properties;
        this.afterAck = afterAck;
        this.published = meters.counter("pixflow.outbox.published");
        this.failures = meters.counter("pixflow.outbox.failures");
        this.staleAcks = meters.counter("pixflow.outbox.stale_ack");
        Gauge.builder("pixflow.outbox.pending", repository, OutboxRepository::pendingCount).register(meters);
    }

    public int publishAvailable() {
        var claimed = repository.claim(properties.owner(), properties.batchSize(), properties.claimTtl());
        claimed.forEach(this::publishOne);
        return claimed.size();
    }

    void publishOne(ClaimedOutboxEvent event) {
        try {
            kafka.send(event.topic(), event.partitionKey(), event.payload())
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            afterAck.afterAck(event);
            if (repository.markPublished(event)) {
                published.increment();
                log.info("operation=outbox_publish result=published eventId={} paymentId={} attempt={}",
                        event.eventId(), event.aggregateId(), event.attemptCount());
            } else {
                staleAcks.increment();
                log.warn("operation=outbox_publish result=stale_ack eventId={} paymentId={}",
                        event.eventId(), event.aggregateId());
            }
        } catch (RelayCrashAfterAckException crash) {
            throw crash;
        } catch (Exception failure) {
            failures.increment();
            repository.markFailed(event, failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                    properties.failureBackoff());
            log.warn("operation=outbox_publish result=failed eventId={} paymentId={} failureType={}",
                    event.eventId(), event.aggregateId(), failure.getClass().getSimpleName());
        }
    }
}
