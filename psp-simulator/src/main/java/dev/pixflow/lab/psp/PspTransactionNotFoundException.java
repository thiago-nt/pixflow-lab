package dev.pixflow.lab.psp;

public class PspTransactionNotFoundException extends RuntimeException {
    public PspTransactionNotFoundException(String reference) { super("synthetic PSP transaction not found"); }
}
