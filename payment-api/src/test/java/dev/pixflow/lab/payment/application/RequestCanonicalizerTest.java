package dev.pixflow.lab.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class RequestCanonicalizerTest {

    @Test
    void isDeterministicForUnicodeAndDelimiters() {
        var command = command("conta|ç", "referência|東京");

        assertThat(RequestCanonicalizer.v2(command))
                .isEqualTo(RequestCanonicalizer.v2(command))
                .isNotEqualTo(RequestCanonicalizer.v2(command("conta", "ç|referência|東京")));
    }

    @Test
    void usesMinorUnitsForEquivalentDecimalRepresentations() {
        var first = new CreatePaymentCommand("account", new BigDecimal("10.0"), "BRL", "ref");
        var second = new CreatePaymentCommand("account", new BigDecimal("10.00"), "BRL", "ref");

        assertThat(RequestCanonicalizer.v2(first)).isEqualTo(RequestCanonicalizer.v2(second));
    }

    private static CreatePaymentCommand command(String account, String reference) {
        return new CreatePaymentCommand(account, new BigDecimal("10.00"), "BRL", reference);
    }
}
