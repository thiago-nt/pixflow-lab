package dev.pixflow.lab.reconciliation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ReconciliationProperties.class)
public class ReconciliationWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReconciliationWorkerApplication.class, args);
    }
}
