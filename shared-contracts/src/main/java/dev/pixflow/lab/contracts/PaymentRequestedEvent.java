package dev.pixflow.lab.contracts;

import java.time.Instant;
import java.util.UUID;

public record PaymentRequestedEvent(UUID eventId, UUID paymentId, String eventType, int schemaVersion,
        Instant occurredAt, String correlationId, long amountMinor, String currency) {}
