package dev.pixflow.lab.outbox;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LabAfterKafkaAckFailureInjector implements AfterKafkaAckHook {
    private static final Logger log = LoggerFactory.getLogger(LabAfterKafkaAckFailureInjector.class);
    private final boolean enabled;
    private final UUID targetEventId;
    private final AtomicBoolean armed = new AtomicBoolean(true);

    LabAfterKafkaAckFailureInjector(boolean enabled, String targetEventId) {
        this.enabled = enabled;
        this.targetEventId = targetEventId == null || targetEventId.isBlank() ? null : UUID.fromString(targetEventId);
    }

    @Override
    public void afterAck(ClaimedOutboxEvent event) {
        if (enabled && targetEventId != null && targetEventId.equals(event.eventId())
                && armed.compareAndSet(true, false)) {
            log.error("operation=lab_failure_injection point=after_kafka_ack eventId={} paymentId={} action=throw_once",
                    event.eventId(), event.aggregateId());
            throw new RelayCrashAfterAckException("synthetic lab failure after Kafka ACK");
        }
    }
}
