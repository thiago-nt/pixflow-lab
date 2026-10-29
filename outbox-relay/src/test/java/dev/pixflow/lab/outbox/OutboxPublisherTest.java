package dev.pixflow.lab.outbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OutboxPublisherTest {

    @Test
    void marksPublishedOnlyAfterKafkaAck() {
        var repository = mock(OutboxRepository.class);
        var kafka = kafka();
        var event = event();
        when(repository.claim(anyString(), anyInt(), any())).thenReturn(List.of(event));
        when(kafka.send(event.topic(), event.partitionKey(), event.payload()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(repository.markPublished(event)).thenReturn(true);

        publisher(repository, kafka, ignored -> {}).publishAvailable();

        var order = inOrder(kafka, repository);
        order.verify(kafka).send(event.topic(), event.partitionKey(), event.payload());
        order.verify(repository).markPublished(event);
        verify(repository, never()).markFailed(any(), anyString(), any());
    }

    @Test
    void kafkaFailureReleasesClaimForRetry() {
        var repository = mock(OutboxRepository.class);
        var kafka = kafka();
        var event = event();
        when(repository.claim(anyString(), anyInt(), any())).thenReturn(List.of(event));
        var failed = new CompletableFuture<SendResult<String, String>>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(kafka.send(event.topic(), event.partitionKey(), event.payload())).thenReturn(failed);

        publisher(repository, kafka, ignored -> {}).publishAvailable();

        verify(repository).markFailed(eq(event), contains("ExecutionException"), eq(Duration.ofMillis(1)));
        verify(repository, never()).markPublished(any());
    }

    @Test
    void crashAfterAckLeavesClaimForExpiryAndPossibleDuplicate() {
        var repository = mock(OutboxRepository.class);
        var kafka = kafka();
        var event = event();
        when(repository.claim(anyString(), anyInt(), any())).thenReturn(List.of(event));
        when(kafka.send(event.topic(), event.partitionKey(), event.payload()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        var publisher = publisher(repository, kafka,
                ignored -> { throw new RelayCrashAfterAckException("test crash"); });

        assertThatThrownBy(publisher::publishAvailable).isInstanceOf(RelayCrashAfterAckException.class);
        verify(repository, never()).markPublished(any());
        verify(repository, never()).markFailed(any(), anyString(), any());
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> kafka() {
        return mock(KafkaTemplate.class);
    }

    private static OutboxPublisher publisher(OutboxRepository repository, KafkaTemplate<String, String> kafka,
            AfterKafkaAckHook hook) {
        var properties = new RelayProperties("test-relay", 10, Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofMillis(1), Duration.ofSeconds(1));
        return new OutboxPublisher(repository, kafka, properties, hook, new SimpleMeterRegistry());
    }

    private static ClaimedOutboxEvent event() {
        var paymentId = UUID.randomUUID();
        return new ClaimedOutboxEvent(UUID.randomUUID(), paymentId, 1, "payment.requested.v1",
                "payment.requested", "{\"eventId\":\"test\"}", paymentId.toString(), Instant.now(),
                "test-relay", UUID.randomUUID(), Instant.now().plusSeconds(30), 1);
    }
}
