package dev.pixflow.lab.worker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
class WorkerConfiguration {
    @Bean RestClient.Builder restClientBuilder() { return RestClient.builder(); }
    @Bean AfterPspCallHook afterPspCallHook() { return () -> {}; }
}
