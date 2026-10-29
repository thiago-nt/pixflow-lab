package dev.pixflow.lab.payment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.pixflow.lab.contracts.PaymentRequestedEvent;
import dev.pixflow.lab.payment.domain.IdempotencyConflictException;
import dev.pixflow.lab.payment.domain.InvalidPaymentException;
import dev.pixflow.lab.payment.domain.Money;
import dev.pixflow.lab.payment.domain.Payment;
import dev.pixflow.lab.payment.domain.PaymentNotFoundException;
import dev.pixflow.lab.payment.domain.PaymentStatus;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final PaymentStore store;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public PaymentService(PaymentStore store, TransactionTemplate transactions, ObjectMapper json, Clock clock) {
        this.store = store;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
    }

    public PaymentResult create(String idempotencyKey, CreatePaymentCommand command, String correlationId) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200) {
            throw new InvalidPaymentException("Idempotency-Key is required and must have at most 200 characters");
        }
        var requestHash = Hashing.sha256(RequestCanonicalizer.v2(command));
        var legacyRequestHash = Hashing.sha256(RequestCanonicalizer.legacy(command));
        var keyDigest = Hashing.sha256(idempotencyKey.trim());
        var existing = store.findIdempotency(command.accountId(), keyDigest);
        if (existing.isPresent()) {
            return existingResult(existing.get(), requestHash, legacyRequestHash, command, correlationId);
        }
        try {
            return transactions.execute(status -> {
                var now = Instant.now(clock);
                var paymentId = UUID.randomUUID();
                var eventId = UUID.randomUUID();
                var payment = new Payment(paymentId, command.accountId(), new Money(command.amount(), command.currency()), command.merchantReference(), PaymentStatus.PENDING, now, now);
                store.insertPayment(payment, requestHash);
                store.insertIdempotency(command.accountId(), keyDigest, requestHash, paymentId);
                store.insertOutbox(eventId, payment, correlationId, eventPayload(eventId, payment, correlationId, now));
                store.insertAudit(paymentId, eventId, correlationId);
                log.info("operation=payment_create paymentId={} result=created correlationId={}", paymentId, correlationId);
                return new PaymentResult(payment, true, correlationId);
            });
        } catch (DataIntegrityViolationException race) {
            for (int attempt = 0; attempt < 20; attempt++) {
                var winner = store.findIdempotency(command.accountId(), keyDigest);
                if (winner.isPresent()) {
                    return existingResult(winner.get(), requestHash, legacyRequestHash, command, correlationId);
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw race;
                }
            }
            throw race;
        }
    }

    private PaymentResult existingResult(PaymentStore.StoredIdempotency existing, String requestHash,
            String legacyRequestHash, CreatePaymentCommand command, String correlationId) {
        var payment = store.findPayment(existing.paymentId())
                .orElseThrow(() -> new PaymentNotFoundException(existing.paymentId()));
        var hashMatches = existing.requestHash().equals(requestHash)
                || existing.requestHash().equals(legacyRequestHash);
        var payloadMatches = payment.accountId().equals(command.accountId())
                && payment.money().amount().compareTo(command.amount()) == 0
                && payment.money().currency().equals(command.currency())
                && payment.merchantReference().equals(command.merchantReference());
        if (!hashMatches || !payloadMatches) {
            log.warn("operation=payment_create paymentId={} result=idempotency_conflict correlationId={}", existing.paymentId(), correlationId);
            throw new IdempotencyConflictException();
        }
        var result = new PaymentResult(payment, false, correlationId);
        log.info("operation=payment_create paymentId={} result=idempotent_replay correlationId={}", existing.paymentId(), correlationId);
        return result;
    }

    private String eventPayload(UUID eventId, Payment payment, String correlationId, Instant occurredAt) {
        try {
            return json.writeValueAsString(new PaymentRequestedEvent(eventId, payment.id(), "payment.requested", 1,
                    occurredAt, correlationId, payment.money().minorUnits(), payment.money().currency()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize outbox event", exception);
        }
    }

    public Payment get(UUID id) {
        return store.findPayment(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    public PaymentPage list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidPaymentException("page must be >= 0 and size between 1 and 100");
        
        }return store.findPayments(page, size);
    }

    public LabPaymentView lab(UUID id) {
        return store.findLabView(id);
    }
}
