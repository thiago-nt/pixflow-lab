package dev.pixflow.lab.payment.application;

import dev.pixflow.lab.payment.domain.Payment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LabPaymentView(Payment payment, String maskedIdempotencyKey, List<OutboxView> outbox, List<AuditView> audit,
        WorkerView worker, PspView psp) {
    public record OutboxView(UUID eventId, String topic, String eventType, Instant createdAt, Instant publishedAt,
            String claimedBy, Instant claimedUntil, int attemptCount, String lastError) {}
    public record AuditView(long auditId, String previousStatus, String newStatus, UUID eventId, Instant occurredAt, String correlationId, String reason) {}
    public record WorkerView(String consumerGroup, UUID eventId, Instant completedAt) {}
    public record PspView(UUID transactionId, String externalReference, String scenario, String outcome, boolean responseLost, Instant createdAt) {}
}
