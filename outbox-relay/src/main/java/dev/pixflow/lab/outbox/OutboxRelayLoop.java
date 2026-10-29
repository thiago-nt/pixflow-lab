package dev.pixflow.lab.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelayLoop {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelayLoop.class);
    private final OutboxPublisher publisher;

    public OutboxRelayLoop(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${pixflow.relay.poll-delay:500ms}")
    public void poll() {
        try {
            publisher.publishAvailable();
        } catch (RelayCrashAfterAckException simulatedCrash) {
            log.error("operation=outbox_poll result=aborted_after_ack failureType={}",
                    simulatedCrash.getClass().getSimpleName());
        }
    }
}
