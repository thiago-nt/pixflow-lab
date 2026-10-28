package dev.pixflow.lab.payment.domain;

import java.util.UUID;
public class PaymentNotFoundException extends RuntimeException { public PaymentNotFoundException(UUID id) { super("payment not found: " + id); } }
