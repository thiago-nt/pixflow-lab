package dev.pixflow.lab.worker;

import java.time.Instant;
import java.util.UUID;

public record PaymentLease(UUID paymentId, String ownerId, long fencingToken, Instant leaseUntil,
        String status, String externalReference, long amountMinor, String currency) {}
