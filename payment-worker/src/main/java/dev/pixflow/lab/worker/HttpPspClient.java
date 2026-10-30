package dev.pixflow.lab.worker;

import dev.pixflow.lab.contracts.PspChargeRequest;
import dev.pixflow.lab.contracts.PspTransactionView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpPspClient implements PspClient {
    private final RestClient client;
    public HttpPspClient(RestClient.Builder builder, @Value("${pixflow.psp.base-url}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }
    @Override public PspTransactionView charge(PspChargeRequest request) {
        try { return client.post().uri("/simulator/transactions").body(request).retrieve().body(PspTransactionView.class); }
        catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 504) throw new PspOutcomeUnknownException("PSP response was lost");
            throw ex;
        }
    }
    @Override public java.util.Optional<PspTransactionView> find(String reference) {
        try { return java.util.Optional.ofNullable(client.get().uri("/simulator/transactions/by-reference/{reference}", reference).retrieve().body(PspTransactionView.class)); }
        catch (RestClientResponseException ex) { if (ex.getStatusCode().value() == 404) return java.util.Optional.empty(); throw ex; }
    }
}
