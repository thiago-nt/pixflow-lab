package dev.pixflow.lab.psp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

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

import dev.pixflow.lab.contracts.PspChargeRequest;
import dev.pixflow.lab.contracts.PspOutcome;
import dev.pixflow.lab.contracts.SimulationScenario;

@JdbcTest
@Import(PspSimulatorService.class)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PspSimulatorIntegrationTest {
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
            for (var migration : List.of("V001__create_payment_core.sql", "V002__payment_api_pending_state.sql",
                    "V003__outbox_claim_ownership.sql", "V004__worker_and_psp_simulator.sql")) {
                var path = Path.of(System.getProperty("user.dir"), "infra", "postgres", "migrations", migration);
                if (!Files.exists(path)) path = Path.of(System.getProperty("user.dir"), "..", "infra", "postgres", "migrations", migration);
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(path));
            }
        }
    }

    @Autowired PspSimulatorService simulator;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE psp_simulator.transactions");
    }

    @Test
    void sameExternalReferenceCreatesOneSyntheticEffect() {
        var request = request(SimulationScenario.APPROVED);
        var first = simulator.charge(request);
        var replay = simulator.charge(new PspChargeRequest(request.paymentId(), request.externalReference(),
                request.amountMinor(), request.currency(), SimulationScenario.REJECTED));

        assertThat(first.transaction().outcome()).isEqualTo(PspOutcome.APPROVED);
        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.transaction().transactionId()).isEqualTo(first.transaction().transactionId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM psp_simulator.transactions", Integer.class)).isEqualTo(1);
    }

    @Test
    void timeoutPersistsEffectAndLosesOnlyTheFirstResponse() {
        var request = request(SimulationScenario.TIMEOUT);
        var first = simulator.charge(request);
        var replay = simulator.charge(request);
        var queried = simulator.findByReference(request.externalReference()).orElseThrow();

        assertThat(first.responseLost()).isTrue();
        assertThat(first.transaction().outcome()).isEqualTo(PspOutcome.APPROVED);
        assertThat(replay.responseLost()).isFalse();
        assertThat(replay.transaction().transactionId()).isEqualTo(first.transaction().transactionId());
        assertThat(queried.responseLost()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM psp_simulator.transactions", Integer.class)).isEqualTo(1);
    }

    @Test
    void referenceCannotBeReusedWithDifferentFinancialPayload() {
        var request = request(SimulationScenario.APPROVED);
        simulator.charge(request);

        assertThatThrownBy(() -> simulator.charge(new PspChargeRequest(request.paymentId(),
                request.externalReference(), request.amountMinor() + 1, request.currency(),
                SimulationScenario.APPROVED))).isInstanceOf(PspReferenceConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM psp_simulator.transactions", Integer.class)).isEqualTo(1);
    }

    private static PspChargeRequest request(SimulationScenario scenario) {
        var paymentId = UUID.randomUUID();
        return new PspChargeRequest(paymentId, "pxf-" + paymentId, 1234, "BRL", scenario);
    }
}
