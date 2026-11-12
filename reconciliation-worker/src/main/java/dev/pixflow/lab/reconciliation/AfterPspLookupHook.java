package dev.pixflow.lab.reconciliation;

@FunctionalInterface
public interface AfterPspLookupHook {
    void afterLookup(ReconciliationClaim claim, PspLookupResult result);
}
