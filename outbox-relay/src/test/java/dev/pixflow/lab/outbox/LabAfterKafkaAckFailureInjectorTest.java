package dev.pixflow.lab.outbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class LabAfterKafkaAckFailureInjectorTest {
    @Test
    void onlyFailsOnceForTheExplicitTargetWhenEnabled() {
        var event = event();
        var injector = new LabAfterKafkaAckFailureInjector(true, event.eventId().toString());

        assertThatThrownBy(() -> injector.afterAck(event)).isInstanceOf(RelayCrashAfterAckException.class);
        assertThatCode(() -> injector.afterAck(event)).doesNotThrowAnyException();
        assertThatCode(() -> injector.afterAck(event(UUID.randomUUID()))).doesNotThrowAnyException();
    }

    @Test
    void isDisabledByDefault() {
        var event = event();
        assertThatCode(() -> new LabAfterKafkaAckFailureInjector(false, event.eventId().toString()).afterAck(event))
                .doesNotThrowAnyException();
    }

    private static ClaimedOutboxEvent event() {
        return event(UUID.randomUUID());
    }

    private static ClaimedOutboxEvent event(UUID eventId) {
        var paymentId = UUID.randomUUID();
        return new ClaimedOutboxEvent(eventId, paymentId, 1, "payment.requested.v1", "payment.requested",
                "{}", paymentId.toString(), Instant.now(), "test", UUID.randomUUID(), Instant.now().plusSeconds(30), 1);
    }
}
