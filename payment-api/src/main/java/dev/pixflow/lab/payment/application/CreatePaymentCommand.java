package dev.pixflow.lab.payment.application;

import dev.pixflow.lab.payment.domain.InvalidPaymentException;
import dev.pixflow.lab.payment.domain.Money;
import java.math.BigDecimal;

public record CreatePaymentCommand(String accountId, BigDecimal amount, String currency, String merchantReference) {
    public CreatePaymentCommand {
        if (accountId == null || accountId.isBlank() || accountId.length() > 100) throw new InvalidPaymentException("accountId is required and must have at most 100 characters");
        if (merchantReference == null || merchantReference.isBlank() || merchantReference.length() > 120) throw new InvalidPaymentException("merchantReference is required and must have at most 120 characters");
        accountId = accountId.trim(); merchantReference = merchantReference.trim();
        new Money(amount, currency);
    }
}
