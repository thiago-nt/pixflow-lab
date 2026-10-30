package dev.pixflow.lab.contracts;

import java.time.Instant;
import java.util.UUID;

public record PspTransactionView(UUID transactionId, UUID paymentId, String externalReference,
        long amountMinor, String currency, SimulationScenario simulationScenario, PspOutcome outcome,
        boolean responseLost, Instant createdAt) {}
