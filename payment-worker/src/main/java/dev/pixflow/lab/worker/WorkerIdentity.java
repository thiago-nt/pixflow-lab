package dev.pixflow.lab.worker;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class WorkerIdentity {
    private final String ownerId;

    public WorkerIdentity(@Value("${pixflow.worker.owner-prefix:payment-worker}") String prefix) {
        this.ownerId = prefix + "-" + UUID.randomUUID();
    }

    public String ownerId() { return ownerId; }
}
