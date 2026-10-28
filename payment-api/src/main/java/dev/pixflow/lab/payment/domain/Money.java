package dev.pixflow.lab.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record Money(BigDecimal amount, String currency) {
    public Money {
        if (amount == null || amount.signum() <= 0) throw new InvalidPaymentException("amount must be positive");
        if (!"BRL".equals(currency)) throw new InvalidPaymentException("currency must be BRL");
        try { amount = amount.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException exception) { throw new InvalidPaymentException("amount supports at most two decimal places"); }
    }
    public long minorUnits() { return amount.movePointRight(2).longValueExact(); }
}
