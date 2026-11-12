package dev.pixflow.lab.payment.application;

@FunctionalInterface
public interface AfterPaymentCommitHook {
    void afterCommit(String idempotencyKey, PaymentResult result);
}
