package com.example.payment.vendor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
class VendorConfig {

    private static final String API_KEY = "dev-api-key";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    // vendor.base-url, or VENDOR_BASE_URL on Fly. The 8081 default is because payment itself
    // listens on 8080 (mock-vendor needs server.port=8081 locally).
    @Bean
    RestClient vendorRestClient(RestClient.Builder builder,
                                @Value("${vendor.base-url:http://localhost:8081}") String baseUrl) {
        var httpClient = HttpClient.newBuilder()
                // The JDK default is HTTP/2, which over plain http tries an h2c upgrade on every
                // request; servers may reject it for POSTs with a body (RST_STREAM).
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);

        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
