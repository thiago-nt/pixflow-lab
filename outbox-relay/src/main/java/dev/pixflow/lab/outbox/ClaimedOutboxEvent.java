package dev.pixflow.lab.outbox;

import java.time.Instant;
import java.util.UUID;

public record ClaimedOutboxEvent(UUID eventId, UUID aggregateId, long aggregateVersion, String topic,
        String eventType, String payload, String partitionKey, Instant createdAt, String claimedBy,
        UUID claimToken, Instant claimedUntil, int attemptCount) {}
