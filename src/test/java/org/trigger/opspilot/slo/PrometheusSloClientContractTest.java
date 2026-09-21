package org.trigger.opspilot.slo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.trigger.opspilot.observability.ObservabilityProperties;
import org.trigger.opspilot.observability.ProviderGuard;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrometheusSloClientContractTest {
    @Test
    void shouldQuerySingleScalarAtRequestedEvaluationTime() throws Exception {
        AtomicReference<URI> requestUri = new AtomicReference<>();
        HttpServer server = server(requestUri, """
                {"status":"success","data":{"resultType":"scalar","result":[1789952400,"9950"]}}
                """);
        try {
            PrometheusSloClient client = client(server.getAddress().getPort());
            Instant at = Instant.parse("2026-09-21T13:00:00Z");

            assertThat(client.query("sum(increase(good[30d]))", at)).hasValue(9950);
            assertThat(parameters(requestUri.get()))
                    .containsEntry("query", "sum(increase(good[30d]))")
                    .containsEntry("time", String.valueOf(at.getEpochSecond()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectAmbiguousMultiSeriesVector() throws Exception {
        HttpServer server = server(new AtomicReference<>(), """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"service":"a"},"value":[1789952400,"10"]},
                  {"metric":{"service":"b"},"value":[1789952400,"20"]}
                ]}}
                """);
        try {
            PrometheusSloClient client = client(server.getAddress().getPort());
            assertThatThrownBy(() -> client.query("sum by(service) (requests)", Instant.now()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("one series");
        } finally {
            server.stop(0);
        }
    }

    private static PrometheusSloClient client(int port) {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getReliability().setMaxAttempts(1);
        properties.getReliability().setBackoff(Duration.ZERO);
        properties.getPrometheus().setEnabled(true);
        properties.getPrometheus().setBaseUrl("http://127.0.0.1:" + port);
        properties.getPrometheus().setConnectTimeout(Duration.ofSeconds(1));
        properties.getPrometheus().setReadTimeout(Duration.ofSeconds(1));
        return new PrometheusSloClient(properties, new ProviderGuard(properties), RestClient.builder());
    }

    private static HttpServer server(AtomicReference<URI> requestUri, String response) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/query", exchange -> respond(exchange, requestUri, response));
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

    private static Map<String, String> parameters(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&")).map(item -> item.split("=", 2))
                .collect(Collectors.toMap(parts -> decode(parts[0]), parts -> decode(parts[1])));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
