package dev.pixflow.lab.reconciliation;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationClaim(UUID paymentId, String externalReference, String ownerId, UUID claimToken,
        long fencingToken, Instant leaseUntil, int attemptNumber, int maxAttempts, String correlationId) { }
