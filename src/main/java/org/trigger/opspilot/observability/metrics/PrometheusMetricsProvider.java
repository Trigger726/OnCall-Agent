package org.trigger.opspilot.observability.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.trigger.opspilot.observability.ObservabilityProperties;
import org.trigger.opspilot.observability.ProviderGuard;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PrometheusMetricsProvider implements MetricsProvider {
    private final ObservabilityProperties.Prometheus properties;
    private final ProviderGuard providerGuard;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public PrometheusMetricsProvider(ObservabilityProperties properties,
                                     ProviderGuard providerGuard,
                                     ObjectMapper objectMapper) {
        this.properties = properties.getPrometheus();
        this.providerGuard = providerGuard;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) this.properties.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) this.properties.getReadTimeout().toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(normalizeBaseUrl(this.properties.getBaseUrl()))
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public String id() {
        return "prometheus-metrics";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean available() {
        return properties.isEnabled();
    }

    @Override
    public MetricsResult query(MetricsQuery query) {
        String expression = buildExpression(properties.getQueryTemplate(), query.resourceCode());
        return providerGuard.execute(id(), () -> request(query, expression));
    }

    private MetricsResult request(MetricsQuery query, String expression) {
        long timestamp = query.end().atZone(ZoneId.systemDefault()).toEpochSecond();
        URI uri = UriComponentsBuilder.fromUriString(normalizeBaseUrl(properties.getBaseUrl()))
                .path("/api/v1/query")
                .queryParam("query", expression)
                .queryParam("time", timestamp)
                .build().encode(StandardCharsets.UTF_8).toUri();
        JsonNode body = restClient.get().uri(uri).retrieve().body(JsonNode.class);
        if (body == null || !"success".equals(body.path("status").asText())) {
            throw new IllegalStateException("Prometheus returned a non-success response");
        }
        List<MetricSample> samples = new ArrayList<>();
        int index = 0;
        for (JsonNode item : body.path("data").path("result")) {
            JsonNode valueNode = item.path("value");
            if (!valueNode.isArray() || valueNode.size() < 2) continue;
            Map<String, String> labels = objectMapper.convertValue(
                    item.path("metric"), new TypeReference<>() {
                    });
            String metricName = labels.getOrDefault("__name__", "prometheus_sample");
            Map<String, String> visibleLabels = new LinkedHashMap<>(labels);
            visibleLabels.remove("__name__");
            double epochSeconds = valueNode.get(0).asDouble();
            LocalDateTime observedAt = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli((long) (epochSeconds * 1000)), ZoneId.systemDefault());
            samples.add(new MetricSample(String.valueOf(index++), query.resourceCode(), metricName,
                    valueNode.get(1).asText(), "raw", Map.copyOf(visibleLabels), observedAt));
        }
        return new MetricsResult(id(), expression,
                normalizeBaseUrl(properties.getBaseUrl()) + "/graph", List.copyOf(samples), List.of());
    }

    static String buildExpression(String template, String resourceCode) {
        String safeResource = resourceCode.replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\n", "\\n");
        return template.contains("%s") ? template.formatted(safeResource) : template;
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null || value.isBlank() ? "http://localhost:9090" : value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }
}
