package dev.pixflow.lab.reconciliation;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pixflow.reconciliation")
public record ReconciliationProperties(String ownerPrefix, Duration leaseTtl, Duration pollDelay, int batchSize,
        int maxAttempts, Duration baseBackoff, Duration maxBackoff, Duration jitter, String resultTopic) {
    public ReconciliationProperties {
        if (ownerPrefix == null || ownerPrefix.isBlank()) throw new IllegalArgumentException("owner prefix is required");
        if (leaseTtl == null || leaseTtl.isNegative() || leaseTtl.isZero()) throw new IllegalArgumentException("lease TTL must be positive");
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("batch size must be 1..100");
        if (maxAttempts < 1 || maxAttempts > 100) throw new IllegalArgumentException("max attempts must be 1..100");
    }
}
