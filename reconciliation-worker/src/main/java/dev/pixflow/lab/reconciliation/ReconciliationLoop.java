package dev.pixflow.lab.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "pixflow.reconciliation.scheduling-enabled", havingValue = "true", matchIfMissing = true)
class ReconciliationLoop {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationLoop.class);
    private final ReconciliationService service;

    ReconciliationLoop(ReconciliationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${pixflow.reconciliation.poll-delay:2s}")
    void poll() {
        try {
            int processed = service.runBatch();
            if (processed > 0) log.info("operation=reconciliation_poll processed={}", processed);
        } catch (RuntimeException failure) {
            log.error("operation=reconciliation_poll result=failed failureType={}",
                    failure.getClass().getSimpleName(), failure);
        }
    }
}
