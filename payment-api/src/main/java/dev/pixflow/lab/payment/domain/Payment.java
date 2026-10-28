package dev.pixflow.lab.payment.domain;

import java.time.Instant;
import java.util.UUID;

public record Payment(UUID id, String accountId, Money money, String merchantReference, PaymentStatus status, Instant createdAt, Instant updatedAt) {}
