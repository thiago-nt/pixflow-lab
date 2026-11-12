package dev.pixflow.lab.payment.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.pixflow.lab.payment.application.LabAfterCommitException;
import dev.pixflow.lab.payment.application.PaymentResult;
import dev.pixflow.lab.payment.domain.Money;
import dev.pixflow.lab.payment.domain.Payment;
import dev.pixflow.lab.payment.domain.PaymentStatus;

class LabFailureInjectionConfigurationTest {
    @Test
    void onlyFailsOnceForCreatedPaymentAndExplicitKey() {
        var hook = new LabFailureInjectionConfiguration().afterPaymentCommitHook(true, "target-key");
        var result = result(true);

        assertThatThrownBy(() -> hook.afterCommit("target-key", result)).isInstanceOf(LabAfterCommitException.class);
        assertThatCode(() -> hook.afterCommit("target-key", result)).doesNotThrowAnyException();
        assertThatCode(() -> hook.afterCommit("other-key", result(true))).doesNotThrowAnyException();
    }

    @Test
    void doesNotFailForReplayOrWhenDisabled() {
        var enabled = new LabFailureInjectionConfiguration().afterPaymentCommitHook(true, "target-key");
        var disabled = new LabFailureInjectionConfiguration().afterPaymentCommitHook(false, "target-key");

        assertThatCode(() -> enabled.afterCommit("target-key", result(false))).doesNotThrowAnyException();
        assertThatCode(() -> disabled.afterCommit("target-key", result(true))).doesNotThrowAnyException();
    }

    private static PaymentResult result(boolean created) {
        var now = Instant.now();
        var payment = new Payment(UUID.randomUUID(), "lab-account", new Money(new BigDecimal("1.00"), "BRL"),
                "lab-reference", PaymentStatus.PENDING, now, now);
        return new PaymentResult(payment, created, "lab-correlation");
    }
}
