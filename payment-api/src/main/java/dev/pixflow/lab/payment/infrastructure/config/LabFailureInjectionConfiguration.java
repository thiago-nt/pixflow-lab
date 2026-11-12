package dev.pixflow.lab.payment.infrastructure.config;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import dev.pixflow.lab.payment.application.AfterPaymentCommitHook;
import dev.pixflow.lab.payment.application.LabAfterCommitException;

@Configuration
public class LabFailureInjectionConfiguration {
    private static final Logger log = LoggerFactory.getLogger(LabFailureInjectionConfiguration.class);

    @Bean
    AfterPaymentCommitHook afterPaymentCommitHook(
            @Value("${pixflow.lab.failure-injection.enabled:false}") boolean enabled,
            @Value("${pixflow.lab.failure-injection.after-commit-idempotency-key:}") String targetKey) {
        if (!enabled || targetKey == null || targetKey.isBlank()) {
            return (idempotencyKey, result) -> { };
        }
        var armed = new AtomicBoolean(true);
        return (idempotencyKey, result) -> {
            if (result.created() && targetKey.equals(idempotencyKey) && armed.compareAndSet(true, false)) {
                var transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
                log.error("operation=lab_failure_injection point=after_commit paymentId={} transactionActive={} action=throw_once",
                        result.payment().id(), transactionActive);
                throw new LabAfterCommitException("synthetic lab failure after PostgreSQL commit");
            }
        };
    }
}
