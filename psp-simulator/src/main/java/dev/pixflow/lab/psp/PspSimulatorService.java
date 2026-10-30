package dev.pixflow.lab.psp;

import dev.pixflow.lab.contracts.PspChargeRequest;
import dev.pixflow.lab.contracts.PspOutcome;
import dev.pixflow.lab.contracts.PspTransactionView;
import dev.pixflow.lab.contracts.SimulationScenario;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PspSimulatorService {
    private final JdbcTemplate jdbc;
    public PspSimulatorService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public ChargeResult charge(PspChargeRequest request) {
        var id = UUID.randomUUID();
        var outcome = request.simulationScenario() == SimulationScenario.REJECTED ? PspOutcome.REJECTED : PspOutcome.APPROVED;
        var lost = request.simulationScenario() == SimulationScenario.TIMEOUT;
        int inserted = jdbc.update("""
                INSERT INTO psp_simulator.transactions(transaction_id,payment_id,external_reference,amount_minor,currency,simulation_scenario,outcome,response_lost)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """, id, request.paymentId(), request.externalReference(), request.amountMinor(), request.currency(),
                request.simulationScenario().name(), outcome.name(), lost);
        var transaction = findByReference(request.externalReference()).orElseThrow();
        if (!transaction.paymentId().equals(request.paymentId()) || transaction.amountMinor() != request.amountMinor()
                || !transaction.currency().equals(request.currency())) {
            throw new PspReferenceConflictException("external reference already belongs to a different synthetic charge");
        }
        return new ChargeResult(transaction, inserted == 1, inserted == 1 && lost);
    }

    @Transactional(readOnly = true)
    public Optional<PspTransactionView> findByReference(String reference) {
        return jdbc.query("""
                SELECT transaction_id,payment_id,external_reference,amount_minor,currency,simulation_scenario,outcome,response_lost,created_at
                FROM psp_simulator.transactions WHERE external_reference=?
                """, (rs, row) -> new PspTransactionView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getLong(4), rs.getString(5).trim(), SimulationScenario.valueOf(rs.getString(6)),
                PspOutcome.valueOf(rs.getString(7)), rs.getBoolean(8), rs.getTimestamp(9).toInstant()), reference).stream().findFirst();
    }

    public record ChargeResult(PspTransactionView transaction, boolean created, boolean responseLost) {}
}
