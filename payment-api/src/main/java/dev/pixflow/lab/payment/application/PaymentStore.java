package dev.pixflow.lab.payment.application;

import java.util.Optional;
import java.util.UUID;

import dev.pixflow.lab.payment.domain.Payment;

public interface PaymentStore {

    Optional<StoredIdempotency> findIdempotency(String accountId, String keyDigest);

    void insertPayment(Payment payment, String requestHash);

    void insertIdempotency(String accountId, String keyDigest, String requestHash, UUID paymentId);

    void insertOutbox(UUID eventId, Payment payment, String correlationId, String payloadJson);

    void insertAudit(UUID paymentId, UUID eventId, String correlationId);

    Optional<Payment> findPayment(UUID id);

    PaymentPage findPayments(int page, int size);

    LabPaymentView findLabView(UUID id);

    record StoredIdempotency(String requestHash, UUID paymentId) {}
}
