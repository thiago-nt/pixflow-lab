package dev.pixflow.lab.payment.application;

import java.nio.charset.StandardCharsets;

import dev.pixflow.lab.payment.domain.Money;

final class RequestCanonicalizer {

    private RequestCanonicalizer() {}

    static String v2(CreatePaymentCommand command) {
        var money = new Money(command.amount(), command.currency());
        return "v2" + field(command.accountId())
                + field(Long.toString(money.minorUnits()))
                + field(money.currency())
                + field(command.merchantReference());
    }

    static String legacy(CreatePaymentCommand command) {
        var money = new Money(command.amount(), command.currency());
        return command.accountId() + "|" + money.amount().toPlainString() + "|"
                + money.currency() + "|" + command.merchantReference();
    }

    private static String field(String value) {
        return ":" + value.getBytes(StandardCharsets.UTF_8).length + ":" + value;
    }
}
