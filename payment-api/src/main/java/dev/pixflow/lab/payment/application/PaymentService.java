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
import io.micrometer.core.instrument.MeterRegistry;

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
    private final AfterPaymentCommitHook afterCommit;
    private final MeterRegistry meters;

    @Autowired
    public PaymentService(PaymentStore store, TransactionTemplate transactions, ObjectMapper json, Clock clock,
            AfterPaymentCommitHook afterCommit, MeterRegistry meters) {
        this.store = store;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
        this.afterCommit = afterCommit;
        this.meters = meters;
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
            var result = transactions.execute(status -> {
                var now = Instant.now(clock);
                var paymentId = UUID.randomUUID();
                var eventId = UUID.randomUUID();
                var payment = new Payment(paymentId, command.accountId(), new Money(command.amount(), command.currency()), command.merchantReference(), PaymentStatus.PENDING, now, now);
                store.insertPayment(payment, requestHash);
                store.insertIdempotency(command.accountId(), keyDigest, requestHash, paymentId);
                store.insertOutbox(eventId, payment, correlationId,
                        eventPayload(eventId, payment, correlationId, now, command.simulationScenario()));
                store.insertAudit(paymentId, eventId, correlationId);
                meters.counter("pixflow.payment.requests", "outcome", "created").increment();
                log.atInfo().addKeyValue("operation", "payment_create").addKeyValue("paymentId", paymentId)
                        .addKeyValue("outcome", "created")
                        .log("payment created");
                return new PaymentResult(payment, true, correlationId);
            });
            afterCommit.afterCommit(idempotencyKey.trim(), result);
            return result;
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
            meters.counter("pixflow.payment.requests", "outcome", "idempotency_conflict").increment();
            log.atWarn().addKeyValue("operation", "payment_create").addKeyValue("paymentId", existing.paymentId())
                    .addKeyValue("outcome", "idempotency_conflict")
                    .addKeyValue("errorCode", "IDEMPOTENCY_CONFLICT").log("payment idempotency conflict");
            throw new IdempotencyConflictException();
        }
        var result = new PaymentResult(payment, false, correlationId);
        meters.counter("pixflow.payment.requests", "outcome", "idempotent_replay").increment();
        log.atInfo().addKeyValue("operation", "payment_create").addKeyValue("paymentId", existing.paymentId())
                .addKeyValue("outcome", "idempotent_replay")
                .log("payment idempotent replay");
        return result;
    }

    private String eventPayload(UUID eventId, Payment payment, String correlationId, Instant occurredAt,
            dev.pixflow.lab.contracts.SimulationScenario simulationScenario) {
        try {
            return json.writeValueAsString(new PaymentRequestedEvent(eventId, payment.id(), "payment.requested", 1,
                    occurredAt, correlationId, payment.money().minorUnits(), payment.money().currency(),
                    simulationScenario));
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
