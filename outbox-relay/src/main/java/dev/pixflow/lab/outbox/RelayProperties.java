package dev.pixflow.lab.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pixflow.relay")
public record RelayProperties(String owner, int batchSize, Duration claimTtl, Duration sendTimeout,
        Duration failureBackoff, Duration pollDelay) {
    public RelayProperties {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("relay owner is required");
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("batch size must be 1..100");
    }
}
