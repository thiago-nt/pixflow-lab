package dev.pixflow.lab.outbox;

@FunctionalInterface
public interface AfterKafkaAckHook {
    void afterAck(ClaimedOutboxEvent event);
}
