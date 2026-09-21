package org.trigger.opspilot.slo;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.trigger.opspilot.observability.ObservabilityProperties;
import org.trigger.opspilot.observability.ProviderGuard;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalDouble;

@Component
public class PrometheusSloClient {
    private final ObservabilityProperties.Prometheus properties;
    private final ProviderGuard providerGuard;
    private final RestClient restClient;

    public PrometheusSloClient(ObservabilityProperties properties, ProviderGuard providerGuard,
                               RestClient.Builder restClientBuilder) {
        this.properties = properties.getPrometheus();
        this.providerGuard = providerGuard;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) this.properties.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) this.properties.getReadTimeout().toMillis());
        this.restClient = restClientBuilder.baseUrl(baseUrl()).requestFactory(requestFactory).build();
    }

    public boolean available() {
        return properties.isEnabled();
    }

    public OptionalDouble query(String expression, Instant at) {
        return providerGuard.execute("prometheus-slo", () -> request(expression, at));
    }

    private OptionalDouble request(String expression, Instant at) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl()).path("/api/v1/query")
                .queryParam("query", expression).queryParam("time", at.getEpochSecond())
                .build().encode(StandardCharsets.UTF_8).toUri();
        JsonNode body = restClient.get().uri(uri).retrieve().body(JsonNode.class);
        if (body == null || !"success".equals(body.path("status").asText())) {
            throw new IllegalStateException("Prometheus returned a non-success response");
        }
        JsonNode data = body.path("data");
        JsonNode value;
        if ("scalar".equals(data.path("resultType").asText())) {
            value = data.path("result");
        } else {
            JsonNode result = data.path("result");
            if (!result.isArray() || result.isEmpty()) return OptionalDouble.empty();
            if (result.size() != 1) throw new IllegalStateException("SLO query must return one series");
            value = result.get(0).path("value");
        }
        if (!value.isArray() || value.size() < 2) return OptionalDouble.empty();
        double parsed = Double.parseDouble(value.get(1).asText());
        if (!Double.isFinite(parsed)) throw new IllegalStateException("SLO query returned a non-finite value");
        return OptionalDouble.of(parsed);
    }

    public String externalRef() {
        return baseUrl() + "/graph";
    }

    private String baseUrl() {
        String value = properties.getBaseUrl();
        String normalized = value == null || value.isBlank() ? "http://localhost:9090" : value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }
}
