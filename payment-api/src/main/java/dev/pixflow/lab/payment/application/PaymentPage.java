package dev.pixflow.lab.payment.application;

import dev.pixflow.lab.payment.domain.Payment;
import java.util.List;
public record PaymentPage(List<Payment> items, int page, int size, long total) {}
