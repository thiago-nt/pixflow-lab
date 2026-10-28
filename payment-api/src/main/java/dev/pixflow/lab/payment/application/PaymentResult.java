package dev.pixflow.lab.payment.application;

import dev.pixflow.lab.payment.domain.Payment;

public record PaymentResult(Payment payment, boolean created, String correlationId) {}
