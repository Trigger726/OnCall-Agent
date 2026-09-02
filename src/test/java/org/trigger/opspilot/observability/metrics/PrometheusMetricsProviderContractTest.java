package org.trigger.opspilot.observability.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.trigger.opspilot.observability.ObservabilityProperties;
import org.trigger.opspilot.observability.ProviderGuard;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusMetricsProviderContractTest {
    @Test
    void shouldCallInstantQueryAndMapVectorResponseToProviderContract() throws Exception {
        AtomicReference<URI> requestUri = new AtomicReference<>();
        HttpServer server = startServer("/api/v1/query", requestUri, """
                {
                  "status":"success",
                  "data":{"resultType":"vector","result":[
                    {"metric":{"__name__":"up","job":"APP-SETTLEMENT","cluster":"prod-east"},
                     "value":[1787111880.0,"1"]}
                  ]}
                }
                """);
        try {
            ObservabilityProperties properties = properties(server.getAddress().getPort());
            PrometheusMetricsProvider provider = new PrometheusMetricsProvider(
                    properties, new ProviderGuard(properties), new ObjectMapper());

            MetricsProvider.MetricsResult result = provider.query(new MetricsProvider.MetricsQuery(
                    1, 1, "APP-SETTLEMENT", "统一结算服务",
                    LocalDateTime.of(2026, 8, 19, 8, 32),
                    LocalDateTime.of(2026, 8, 19, 9, 46)));

            assertThat(result.providerId()).isEqualTo("prometheus-metrics");
            assertThat(result.query()).isEqualTo("up{job=\"APP-SETTLEMENT\"}");
            assertThat(result.externalRef()).endsWith("/graph");
            assertThat(result.samples()).singleElement().satisfies(sample -> {
                assertThat(sample.metricName()).isEqualTo("up");
                assertThat(sample.value()).isEqualTo("1");
                assertThat(sample.labels()).containsEntry("job", "APP-SETTLEMENT")
                        .containsEntry("cluster", "prod-east")
                        .doesNotContainKey("__name__");
            });
            assertThat(queryParameters(requestUri.get()))
                    .containsEntry("query", "up{job=\"APP-SETTLEMENT\"}")
                    .containsKey("time");
        } finally {
            server.stop(0);
        }
    }

    private static ObservabilityProperties properties(int port) {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getReliability().setMaxAttempts(1);
        properties.getReliability().setBackoff(Duration.ZERO);
        properties.getPrometheus().setEnabled(true);
        properties.getPrometheus().setBaseUrl("http://127.0.0.1:" + port);
        properties.getPrometheus().setQueryTemplate("up{job=\"%s\"}");
        properties.getPrometheus().setConnectTimeout(Duration.ofSeconds(1));
        properties.getPrometheus().setReadTimeout(Duration.ofSeconds(1));
        return properties;
    }

    private static HttpServer startServer(String path, AtomicReference<URI> requestUri, String response)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> respond(exchange, requestUri, response));
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, AtomicReference<URI> requestUri, String response)
            throws IOException {
        requestUri.set(exchange.getRequestURI());
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static Map<String, String> queryParameters(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(item -> item.split("=", 2))
                .collect(Collectors.toMap(parts -> decode(parts[0]), parts -> decode(parts[1])));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
