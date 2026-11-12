package dev.pixflow.lab.reconciliation;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class ReconciliationConfiguration {
    @Bean
    RestClient.Builder reconciliationRestClientBuilder() {
        var httpClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(java.time.Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory);
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
