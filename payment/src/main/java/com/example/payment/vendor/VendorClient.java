package com.example.payment.vendor;

import com.example.vendor.api.CreatePayoutRequest;
import com.example.vendor.api.VendorPaths;
import com.example.vendor.api.VendorPayment;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class VendorClient {

    private final RestClient restClient;

    VendorClient(RestClient vendorRestClient) {
        this.restClient = vendorRestClient;
    }

    public VendorPayment createPayout(UUID ref, long amount, String currency) {
        return restClient.post()
                .uri(VendorPaths.PAYMENT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreatePayoutRequest(ref, amount, currency))
                .retrieve()
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    throw new VendorUnavailableException(res.getStatusCode(), readBody(res));
                })
                .onStatus(s -> s.value() == 422, (req, res) -> {
                    throw new VendorRejectedException(readBody(res));
                })
                .body(VendorPayment.class);
    }

    private static String readBody(ClientHttpResponse res) throws IOException {
        return new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
    }
}
