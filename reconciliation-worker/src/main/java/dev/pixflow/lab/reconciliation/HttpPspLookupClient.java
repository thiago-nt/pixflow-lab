package dev.pixflow.lab.reconciliation;

import dev.pixflow.lab.contracts.PspOutcome;
import dev.pixflow.lab.contracts.PspTransactionView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
class HttpPspLookupClient implements PspLookupClient {
    private final RestClient client;

    HttpPspLookupClient(RestClient.Builder builder, @Value("${pixflow.psp.base-url}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    @Override
    public PspLookupResult lookup(String externalReference) {
        try {
            var transaction = client.get().uri("/simulator/transactions/by-reference/{reference}", externalReference)
                    .retrieve().body(PspTransactionView.class);
            if (transaction == null || transaction.outcome() == null) return PspLookupResult.unknown("PSP returned no final outcome");
            return transaction.outcome() == PspOutcome.APPROVED
                    ? PspLookupResult.approved(transaction) : PspLookupResult.rejected(transaction);
        } catch (RestClientResponseException response) {
            if (response.getStatusCode().value() == 404) return PspLookupResult.notFound();
            return PspLookupResult.temporaryFailure("PSP HTTP " + response.getStatusCode().value());
        } catch (ResourceAccessException timeout) {
            return PspLookupResult.temporaryFailure("PSP lookup timeout or connection failure");
        }
    }
}
