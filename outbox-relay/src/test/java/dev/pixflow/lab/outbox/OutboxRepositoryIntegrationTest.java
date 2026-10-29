package dev.pixflow.lab.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@JdbcTest
@Import(OutboxRepository.class)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxRepositoryIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine")
            .withDatabaseName("pixflow").withUsername("pixflow_app").withPassword("test-only");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeAll
    static void schema() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            for (var migration : List.of("V001__create_payment_core.sql", "V002__payment_api_pending_state.sql", "V003__outbox_claim_ownership.sql")) {
                var path = Path.of(System.getProperty("user.dir"), "infra", "postgres", "migrations", migration);
                if (!Files.exists(path)) path = Path.of(System.getProperty("user.dir"), "..", "infra", "postgres", "migrations", migration);
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(path));
            }
        }
    }

    @Autowired OutboxRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE payment_audit,outbox_events,idempotency_keys,payments CASCADE");
    }

    @Test
    void twoRelaysCompeteWithoutDoubleClaiming() throws Exception {
        insertEvent();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return repository.claim("relay-a", 1, Duration.ofSeconds(30)); });
            var second = executor.submit(() -> { start.await(); return repository.claim("relay-b", 1, Duration.ofSeconds(30)); });
            start.countDown();
            var claimed = first.get().size() + second.get().size();
            assertThat(claimed).isEqualTo(1);
        }
    }

    @Test
    void expiredClaimIsRecoveredAndOldOwnerCannotAcknowledge() {
        insertEvent();
        var oldClaim = repository.claim("relay-old", 1, Duration.ofMillis(50)).getFirst();
        jdbc.update("UPDATE outbox_events SET claimed_until=clock_timestamp()-interval '1 second' WHERE event_id=?", oldClaim.eventId());

        var newClaim = repository.claim("relay-new", 1, Duration.ofSeconds(30)).getFirst();

        assertThat(newClaim.claimToken()).isNotEqualTo(oldClaim.claimToken());
        assertThat(repository.markPublished(oldClaim)).isFalse();
        assertThat(repository.markPublished(newClaim)).isTrue();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events", Boolean.class)).isTrue();
    }

    @Test
    void failedClaimIsReleasedWithSanitizedErrorAndBackoff() {
        insertEvent();
        var claim = repository.claim("relay-a", 1, Duration.ofSeconds(30)).getFirst();

        assertThat(repository.markFailed(claim, "network\nsecret-free", Duration.ZERO)).isTrue();
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_events", String.class)).isEqualTo("network secret-free");
        assertThat(repository.claim("relay-b", 1, Duration.ofSeconds(30))).hasSize(1);
    }

    private void insertEvent() {
        var paymentId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payments(payment_id,tenant_id,request_hash,amount_minor,currency,status,external_reference,merchant_reference)
                VALUES (?, 'test', repeat('a',64), 100, 'BRL', 'PENDING', ?, 'test-ref')
                """, paymentId, "pxf-" + paymentId);
        jdbc.update("""
                INSERT INTO outbox_events(event_id,aggregate_id,aggregate_version,topic,event_type,payload_json,partition_key)
                VALUES (?,?,1,'payment.requested.v1','payment.requested',?::jsonb,?)
                """, eventId, paymentId, "{\"eventId\":\"" + eventId + "\",\"paymentId\":\"" + paymentId + "\"}", paymentId.toString());
    }
}
