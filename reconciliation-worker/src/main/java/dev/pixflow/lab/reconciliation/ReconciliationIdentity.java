package dev.pixflow.lab.reconciliation;

import java.util.UUID;

public record ReconciliationIdentity(String ownerId) {
    static ReconciliationIdentity create(String prefix) {
        return new ReconciliationIdentity(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
    }
}
