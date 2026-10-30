package dev.pixflow.lab.contracts;

import java.util.UUID;

public record PspChargeRequest(UUID paymentId, String externalReference, long amountMinor,
        String currency, SimulationScenario simulationScenario) {}
