package dev.pixflow.lab.payment.infrastructure.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class PaymentStateMetrics {
    PaymentStateMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
        Gauge.builder("pixflow.payments.unknown", jdbc, source ->
                source.queryForObject("SELECT count(*) FROM payments WHERE status='UNKNOWN'", Long.class))
                .description("Current number of payments awaiting reconciliation")
                .register(meters);
    }
}
