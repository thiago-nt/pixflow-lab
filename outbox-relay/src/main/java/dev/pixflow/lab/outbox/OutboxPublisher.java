package dev.pixflow.lab.outbox;

import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Service;
import org.slf4j.MDC;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

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
    private final MeterRegistry meters;
    private final ObjectMapper json;

    public OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafka,
            RelayProperties properties, AfterKafkaAckHook afterAck, MeterRegistry meters, ObjectMapper json) {
        this.repository = repository;
        this.kafka = kafka;
        this.properties = properties;
        this.afterAck = afterAck;
        this.meters = meters;
        this.json = json;
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
        var sample = Timer.start(meters);
        var correlationId = correlationId(event);
        MDC.put("correlationId", correlationId);
        MDC.put("paymentId", event.aggregateId().toString());
        MDC.put("eventId", event.eventId().toString());
        try {
            var record = new ProducerRecord<String, String>(event.topic(), event.partitionKey(), event.payload());
            addHeader(record, "pixflow-correlation-id", correlationId);
            addHeader(record, "pixflow-event-id", event.eventId().toString());
            addHeader(record, "pixflow-payment-id", event.aggregateId().toString());
            kafka.send(record)
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            afterAck.afterAck(event);
            if (repository.markPublished(event)) {
                published.increment();
                sample.stop(Timer.builder("pixflow.outbox.publish.duration").tag("outcome", "published").register(meters));
                log.atInfo().addKeyValue("operation", "outbox_publish").addKeyValue("outcome", "published")
                        .addKeyValue("attempt", event.attemptCount())
                        .log("outbox event published");
            } else {
                staleAcks.increment();
                sample.stop(Timer.builder("pixflow.outbox.publish.duration").tag("outcome", "stale_ack").register(meters));
                log.atWarn().addKeyValue("operation", "outbox_publish").addKeyValue("outcome", "stale_ack")
                        .log("stale outbox acknowledgement rejected");
            }
        } catch (RelayCrashAfterAckException crash) {
            sample.stop(Timer.builder("pixflow.outbox.publish.duration").tag("outcome", "crash_after_ack").register(meters));
            throw crash;
        } catch (Exception failure) {
            failures.increment();
            repository.markFailed(event, failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                    properties.failureBackoff());
            sample.stop(Timer.builder("pixflow.outbox.publish.duration").tag("outcome", "failed").register(meters));
            log.atWarn().addKeyValue("operation", "outbox_publish").addKeyValue("outcome", "failed")
                    .addKeyValue("errorCode", failure.getClass().getSimpleName())
                    .log("outbox publication failed");
        } finally {
            MDC.remove("eventId");
            MDC.remove("paymentId");
            MDC.remove("correlationId");
        }
    }

    private String correlationId(ClaimedOutboxEvent event) {
        try {
            var value = json.readTree(event.payload()).path("correlationId").asText();
            return value.isBlank() ? "unavailable" : value.substring(0, Math.min(100, value.length()));
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    private static void addHeader(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
