package dev.pixflow.lab.payment.application;

import dev.pixflow.lab.payment.domain.Payment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LabPaymentView(Payment payment, String maskedIdempotencyKey, List<OutboxView> outbox, List<AuditView> audit,
        WorkerView worker, PspView psp, LeaseView lease, List<LeaseAuditView> leaseHistory,
        ReconciliationView reconciliation, List<ReconciliationAuditView> reconciliationHistory) {
    public record OutboxView(UUID eventId, String topic, String eventType, Instant createdAt, Instant publishedAt,
            String claimedBy, Instant claimedUntil, int attemptCount, String lastError) {}
    public record AuditView(long auditId, String previousStatus, String newStatus, UUID eventId, Instant occurredAt, String correlationId, String reason) {}
    public record WorkerView(String consumerGroup, UUID eventId, Instant completedAt) {}
    public record PspView(UUID transactionId, String externalReference, String scenario, String outcome, boolean responseLost, Instant createdAt) {}
    public record LeaseView(String ownerId, long fencingToken, Instant leaseUntil) {}
    public record LeaseAuditView(UUID leaseEventId, String ownerId, long fencingToken, String eventType,
            Instant leaseUntil, UUID eventId, String correlationId, String detail, Instant occurredAt) {}
    public record ReconciliationView(int attemptCount, int maxAttempts, Instant lastAttemptAt, Instant nextAttemptAt,
            String lastResult, String lastError, String claimedBy, Instant claimedUntil, Instant completedAt) {}
    public record ReconciliationAuditView(long auditId, int attemptNumber, String ownerId, long fencingToken,
            String correlationId, String externalReference, String outcome, UUID pspTransactionId,
            String detail, Instant occurredAt) {}
}
