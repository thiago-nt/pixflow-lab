package dev.pixflow.lab.reconciliation;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.slf4j.MDC;

@Configuration
class ReconciliationConfiguration {
    @Bean
    RestClient.Builder reconciliationRestClientBuilder() {
        var httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(java.time.Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).requestInterceptor((request, body, execution) -> {
            var correlationId = MDC.get("correlationId");
            if (correlationId != null) request.getHeaders().set("X-Correlation-ID", correlationId);
            return execution.execute(request, body);
        });
    }

    @Bean
    ReconciliationIdentity reconciliationIdentity(ReconciliationProperties properties) {
        return ReconciliationIdentity.create(properties.ownerPrefix());
    }

    @Bean
    AfterPspLookupHook afterPspLookupHook() {
        return (claim, result) -> { };
    }
}
