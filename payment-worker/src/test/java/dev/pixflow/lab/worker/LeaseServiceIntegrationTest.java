package dev.pixflow.lab.worker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pixflow.lab.contracts.PaymentRequestedEvent;
import dev.pixflow.lab.contracts.SimulationScenario;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@JdbcTest
@Import({LeaseService.class, LeaseServiceIntegrationTest.Config.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LeaseServiceIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.4-alpine")
            .withDatabaseName("pixflow").withUsername("pixflow_app").withPassword("test-only");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
    }
    @BeforeAll static void schema() throws Exception {
        try (var connection=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())) {
            for (var migration: List.of("V001__create_payment_core.sql","V002__payment_api_pending_state.sql","V003__outbox_claim_ownership.sql","V004__worker_and_psp_simulator.sql","V005__payment_worker_lease_fencing.sql")) {
                var path=Path.of(System.getProperty("user.dir"),"infra","postgres","migrations",migration);
                if (!Files.exists(path)) path=Path.of(System.getProperty("user.dir"),"..","infra","postgres","migrations",migration);
                ScriptUtils.executeSqlScript(connection,new FileSystemResource(path));
            }
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired LeaseService leases;
    @Autowired TransactionTemplate transactionTemplate;
    private UUID paymentId;
    private PaymentRequestedEvent event;

    @BeforeEach void setUp() {
        jdbc.execute("TRUNCATE payment_lease_audit,worker_deferred_events,payment_audit,outbox_events,inbox_processed,idempotency_keys,payments RESTART IDENTITY CASCADE");
        paymentId=UUID.randomUUID();
        jdbc.update("INSERT INTO payments(payment_id,tenant_id,request_hash,amount_minor,currency,status,external_reference,merchant_reference) VALUES (?, 'acct-test','hash',1234,'BRL','PENDING',?, 'synthetic')",paymentId,"pxf-"+paymentId);
        event=new PaymentRequestedEvent(UUID.randomUUID(),paymentId,"payment.requested",1,Instant.now(),"corr-lease",1234,"BRL",SimulationScenario.APPROVED);
    }

    @Test void expiredOwnerIsFencedAndOnlyNewTokenCanPersist() {
        var a=leases.acquire(paymentId,"worker-A",event).orElseThrow();
        jdbc.update("UPDATE payments SET lease_until=clock_timestamp()-interval '1 second' WHERE payment_id=?",paymentId);
        var b=leases.acquire(paymentId,"worker-B",event).orElseThrow();
        assertThat(b.fencingToken()).isGreaterThan(a.fencingToken());
        int accepted=jdbc.update("UPDATE payments SET status='SUCCEEDED' WHERE payment_id=? AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp() AND status='PENDING'",paymentId,b.ownerId(),b.fencingToken());
        assertThat(accepted).isEqualTo(1);
        int stale=jdbc.update("UPDATE payments SET status='FAILED' WHERE payment_id=? AND lease_owner=? AND fencing_token=? AND lease_until>clock_timestamp() AND status='PENDING'",paymentId,a.ownerId(),a.fencingToken());
        assertThat(stale).isZero();
        leases.recordRejected(a,event,"test stale worker write rejected");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE payment_id=?",String.class,paymentId)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_lease_audit WHERE payment_id=? AND event_type='WRITE_REJECTED' AND fencing_token=?",Integer.class,paymentId,a.fencingToken())).isEqualTo(1);
    }

    @Test void simultaneousAcquisitionHasOneOwner() throws Exception {
        var ready=new CountDownLatch(2); var start=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var one=pool.submit(()->{ready.countDown();start.await();return leases.acquire(paymentId,"worker-A",event);});
            var two=pool.submit(()->{ready.countDown();start.await();return leases.acquire(paymentId,"worker-B",event);});
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue(); start.countDown();
            var grants=List.of(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));
            assertThat(grants.stream().filter(java.util.Optional::isPresent)).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT owner_id) FROM payment_lease_audit WHERE payment_id=? AND event_type='ACQUIRED'",Integer.class,paymentId)).isEqualTo(1);
        }
    }

    @Test void renewalRequiresCurrentUnexpiredOwnerAndTokenAndRestartIncrementsToken() {
        var a=leases.acquire(paymentId,"worker-A",event).orElseThrow();
        assertThat(leases.renew(a,event)).isPresent();
        var foreign=new PaymentLease(a.paymentId(),"worker-B",a.fencingToken(),a.leaseUntil(),a.status(),a.externalReference(),a.amountMinor(),a.currency());
        assertThat(leases.renew(foreign,event)).isEmpty();
        jdbc.update("UPDATE payments SET lease_until=clock_timestamp()-interval '1 second' WHERE payment_id=?",paymentId);
        assertThat(leases.renew(a,event)).isEmpty();
        var restarted=leases.acquire(paymentId,"worker-B-restart",event).orElseThrow();
        assertThat(restarted.fencingToken()).isGreaterThan(a.fencingToken());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_lease_audit WHERE payment_id=? AND event_type='RENEWED'",Integer.class,paymentId)).isEqualTo(1);
    }

    @Test void busyPaymentIsDurablyDeferredForLaterKafkaRedelivery() throws Exception {
        var current=leases.acquire(paymentId,"worker-A",event).orElseThrow();
        var retry=new PaymentRequestedEvent(UUID.randomUUID(),paymentId,"payment.requested",1,Instant.now(),"corr-defer",1234,"BRL",SimulationScenario.APPROVED);
        leases.defer(current,retry,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(retry),"lease busy");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM worker_deferred_events WHERE event_id=?",Integer.class,retry.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_lease_audit WHERE event_id=? AND event_type='BUSY_DEFERRED'",Integer.class,retry.eventId())).isEqualTo(1);
    }

    @Test void deferredPublisherKeepsRowsUntilKafkaAckAndRetriesFailures() throws Exception {
        var current=leases.acquire(paymentId,"worker-A",event).orElseThrow();
        var retry=new PaymentRequestedEvent(UUID.randomUUID(),paymentId,"payment.requested",1,Instant.now(),"corr-defer-publish",1234,"BRL",SimulationScenario.APPROVED);
        var payload=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(retry);
        leases.defer(current,retry,payload,"lease busy");
        jdbc.update("UPDATE worker_deferred_events SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE event_id=?",retry.eventId());
        @SuppressWarnings("unchecked") var kafka=(org.springframework.kafka.core.KafkaTemplate<String,String>)org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaTemplate.class);
        org.mockito.Mockito.when(kafka.send(org.mockito.ArgumentMatchers.any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("broker down")),
                        java.util.concurrent.CompletableFuture.completedFuture(null));
        var publisher=new DeferredEventPublisher(jdbc,transactionTemplate,kafka,new WorkerIdentity("deferred-relay"),"payment.requested.v1",20000);
        publisher.publishOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM worker_deferred_events WHERE event_id=?",Integer.class,retry.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM worker_deferred_events WHERE event_id=?",Integer.class,retry.eventId())).isEqualTo(1);
        jdbc.update("UPDATE worker_deferred_events SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE event_id=?",retry.eventId());
        publisher.publishOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM worker_deferred_events WHERE event_id=?",Integer.class,retry.eventId())).isZero();
        org.mockito.Mockito.verify(kafka,org.mockito.Mockito.times(2)).send(org.mockito.ArgumentMatchers.any(org.apache.kafka.clients.producer.ProducerRecord.class));
    }
    @TestConfiguration static class Config {
        @Bean TransactionTemplate transactionTemplate(PlatformTransactionManager manager) { return new TransactionTemplate(manager); }
    }
}
