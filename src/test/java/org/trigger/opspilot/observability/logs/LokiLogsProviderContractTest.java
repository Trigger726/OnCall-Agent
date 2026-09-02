package org.trigger.opspilot.observability.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.trigger.opspilot.observability.LogRedactor;
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

class LokiLogsProviderContractTest {
    @Test
    void shouldCallRangeQueryMapStructuredLogAndRedactBeforeReturningEvidence() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = startServer(captured, """
                {
                  "status":"success",
                  "data":{"resultType":"streams","result":[
                    {"stream":{"resource_code":"APP-SETTLEMENT","app":"settlement-api"},
                     "values":[["1787111862000000000",
                       "{\\\"message\\\":\\\"request failed token=fake-loki-token\\\",\\\"level\\\":\\\"ERROR\\\",\\\"traceId\\\":\\\"trace-loki-1\\\",\\\"clientIp\\\":\\\"10.20.8.15\\\",\\\"api_key\\\":\\\"fake-key\\\"}"]]}
                  ]}
                }
                """);
        try {
            ObservabilityProperties properties = properties(server.getAddress().getPort());
            LokiLogsProvider provider = new LokiLogsProvider(properties, new ProviderGuard(properties),
                    new ObjectMapper(), new LogRedactor());
            LocalDateTime start = LocalDateTime.of(2026, 8, 19, 8, 32);
            LocalDateTime end = LocalDateTime.of(2026, 8, 19, 9, 46);

            LogsProvider.LogsResult result = provider.query(new LogsProvider.LogsQuery(
                    1, 1, "APP-SETTLEMENT", "统一结算服务", start, end, 30));

            assertThat(result.providerId()).isEqualTo("loki-logs");
            assertThat(result.query()).isEqualTo("{resource_code=\"APP-SETTLEMENT\"}");
            assertThat(result.externalRef()).isEqualTo("http://127.0.0.1:" + server.getAddress().getPort());
            assertThat(result.redactedFields()).isEqualTo(3);
            assertThat(result.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.resourceCode()).isEqualTo("APP-SETTLEMENT");
                assertThat(entry.level()).isEqualTo("ERROR");
                assertThat(entry.loggerName()).isEqualTo("settlement-api");
                assertThat(entry.traceId()).isEqualTo("trace-loki-1");
                assertThat(entry.message()).contains("token=***").doesNotContain("fake-loki-token");
                assertThat(entry.metadata()).containsEntry("clientIp", "10.20.8.*")
                        .containsEntry("api_key", "***");
            });

            CapturedRequest request = captured.get();
            assertThat(request.tenant()).isEqualTo("energy-prod");
            assertThat(request.authorization()).isEqualTo("Bearer local-contract-token");
            assertThat(queryParameters(request.uri()))
                    .containsEntry("query", "{resource_code=\"APP-SETTLEMENT\"}")
                    .containsEntry("limit", "30")
                    .containsEntry("direction", "backward")
                    .containsKeys("start", "end");
        } finally {
            server.stop(0);
        }
    }

    private static ObservabilityProperties properties(int port) {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getReliability().setMaxAttempts(1);
        properties.getReliability().setBackoff(Duration.ZERO);
        properties.getLoki().setEnabled(true);
        properties.getLoki().setBaseUrl("http://127.0.0.1:" + port);
        properties.getLoki().setQueryTemplate("{resource_code=\"%s\"}");
        properties.getLoki().setTenantId("energy-prod");
        properties.getLoki().setBearerToken("local-contract-token");
        properties.getLoki().setConnectTimeout(Duration.ofSeconds(1));
        properties.getLoki().setReadTimeout(Duration.ofSeconds(1));
        return properties;
    }

    private static HttpServer startServer(AtomicReference<CapturedRequest> captured, String response)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/loki/api/v1/query_range", exchange -> respond(exchange, captured, response));
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, AtomicReference<CapturedRequest> captured, String response)
            throws IOException {
        captured.set(new CapturedRequest(exchange.getRequestURI(),
                exchange.getRequestHeaders().getFirst("X-Scope-OrgID"),
                exchange.getRequestHeaders().getFirst("Authorization")));
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

    private record CapturedRequest(URI uri, String tenant, String authorization) {
    }
}
