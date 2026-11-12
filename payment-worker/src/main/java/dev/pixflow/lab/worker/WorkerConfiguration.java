package dev.pixflow.lab.worker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.slf4j.MDC;

@Configuration
class WorkerConfiguration {
    @Bean RestClient.Builder restClientBuilder() { return RestClient.builder().requestInterceptor((request, body, execution) -> {
        var correlationId = MDC.get("correlationId");
        if (correlationId != null) request.getHeaders().set("X-Correlation-ID", correlationId);
        return execution.execute(request, body);
    }); }
    @Bean AfterPspCallHook afterPspCallHook() { return () -> {}; }
}
