package dev.pixflow.lab.outbox;

public final class RelayCrashAfterAckException extends RuntimeException {
    public RelayCrashAfterAckException(String message) { super(message); }
}
