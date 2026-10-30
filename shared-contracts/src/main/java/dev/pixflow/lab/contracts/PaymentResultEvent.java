package dev.pixflow.lab.contracts;

import java.time.Instant;
import java.util.UUID;

public record PaymentResultEvent(UUID eventId, UUID paymentId, String eventType, int schemaVersion,
        Instant occurredAt, String correlationId, String status, String resultCode) {}
