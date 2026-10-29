package dev.pixflow.lab.outbox;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RelayConfiguration {
    @Bean
    AfterKafkaAckHook afterKafkaAckHook() {
        return event -> { };
    }
}
