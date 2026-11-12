package dev.pixflow.lab.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class RelayConfiguration {
    @Bean
    AfterKafkaAckHook afterKafkaAckHook(
            @Value("${pixflow.lab.failure-injection.enabled:false}") boolean enabled,
            @Value("${pixflow.lab.failure-injection.after-kafka-ack-event-id:}") String targetEventId) {
        return new LabAfterKafkaAckFailureInjector(enabled, targetEventId);
    }
}
