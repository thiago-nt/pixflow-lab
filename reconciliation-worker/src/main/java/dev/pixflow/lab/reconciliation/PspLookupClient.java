package dev.pixflow.lab.reconciliation;

public interface PspLookupClient {
    PspLookupResult lookup(String externalReference);
}
