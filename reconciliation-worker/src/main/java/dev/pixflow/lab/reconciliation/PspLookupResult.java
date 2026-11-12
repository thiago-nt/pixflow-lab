package dev.pixflow.lab.reconciliation;

import dev.pixflow.lab.contracts.PspTransactionView;

public record PspLookupResult(Status status, PspTransactionView transaction, String detail) {
    public enum Status { APPROVED, REJECTED, UNKNOWN, NOT_FOUND, TEMPORARY_FAILURE }

    public static PspLookupResult approved(PspTransactionView transaction) { return new PspLookupResult(Status.APPROVED, transaction, "PSP confirmed approval"); }
    public static PspLookupResult rejected(PspTransactionView transaction) { return new PspLookupResult(Status.REJECTED, transaction, "PSP confirmed rejection"); }
    public static PspLookupResult unknown(String detail) { return new PspLookupResult(Status.UNKNOWN, null, detail); }
    public static PspLookupResult notFound() { return new PspLookupResult(Status.NOT_FOUND, null, "external reference not found"); }
    public static PspLookupResult temporaryFailure(String detail) { return new PspLookupResult(Status.TEMPORARY_FAILURE, null, detail); }
}
