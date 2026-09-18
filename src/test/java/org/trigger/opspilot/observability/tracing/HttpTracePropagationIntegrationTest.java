package org.trigger.opspilot.observability.tracing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.trigger.opspilot.observability.logs.LogsProvider;
import org.trigger.opspilot.observability.logs.LokiLogsProvider;
import org.trigger.opspilot.observability.metrics.MetricsProvider;
import org.trigger.opspilot.observability.metrics.PrometheusMetricsProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-http-tracing-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false",
        "opspilot.agent.recovery.enabled=false",
        "opspilot.observability.reliability.max-attempts=1",
        "opspilot.observability.prometheus.enabled=true",
        "opspilot.observability.loki.enabled=true",
        "management.tracing.enabled=true",
        "management.tracing.sampling.probability=1.0",
        "management.tracing.propagation.produce=W3C"
})
@AutoConfigureObservability
@Import(HttpTracePropagationIntegrationTest.ExporterConfig.class)
class HttpTracePropagationIntegrationTest {
    private static final AtomicReference<String> PROMETHEUS_TRACEPARENT = new AtomicReference<>();
    private static final AtomicReference<String> LOKI_TRACEPARENT = new AtomicReference<>();
    private static final CapturingSpanExporter EXPORTER = new CapturingSpanExporter();
    private static final HttpServer DOWNSTREAM = startDownstream();

    @Autowired private Tracer tracer;
    @Autowired private OpenTelemetry openTelemetry;
    @Autowired private OpsPilotTracing tracing;
    @Autowired private PrometheusMetricsProvider prometheus;
    @Autowired private LokiLogsProvider loki;

    @BeforeEach
    void clearCapturedTelemetry() {
        PROMETHEUS_TRACEPARENT.set(null);
        LOKI_TRACEPARENT.set(null);
        EXPORTER.clear();
    }

    @DynamicPropertySource
    static void downstreamProperties(DynamicPropertyRegistry registry) {
        String baseUrl = "http://127.0.0.1:" + DOWNSTREAM.getAddress().getPort();
        registry.add("opspilot.observability.prometheus.base-url", () -> baseUrl);
        registry.add("opspilot.observability.loki.base-url", () -> baseUrl);
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.stop(0);
    }

    @Test
    void shouldPropagateW3cTraceContextToPrometheusAndLoki() {
        Span root = tracer.nextSpan().name("provider-propagation-test").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(root)) {
            LocalDateTime end = LocalDateTime.of(2026, 9, 18, 14, 0);
            tracing.traceProviderQuery("metrics", prometheus.id(), () -> prometheus.query(
                    new MetricsProvider.MetricsQuery(
                            1, 1, "APP-AUTH", "认证服务", end.minusMinutes(5), end)));
            tracing.traceProviderQuery("logs", loki.id(), () -> loki.query(
                    new LogsProvider.LogsQuery(
                            1, 1, "APP-AUTH", "认证服务", end.minusMinutes(5), end, 10)));
        } finally {
            root.end();
        }

        assertThat(openTelemetry).isInstanceOf(OpenTelemetrySdk.class);
        OpenTelemetrySdk sdk = (OpenTelemetrySdk) openTelemetry;
        assertThat(sdk.getSdkTracerProvider().forceFlush().join(5, TimeUnit.SECONDS).isSuccess()).isTrue();

        String prometheusClientSpanId = assertTraceparent(PROMETHEUS_TRACEPARENT.get(), root);
        String lokiClientSpanId = assertTraceparent(LOKI_TRACEPARENT.get(), root);
        List<SpanData> trace = EXPORTER.spans(root.context().traceId());
        List<SpanData> providerSpans = trace.stream()
                .filter(span -> "opspilot.provider.query".equals(span.getName())).toList();
        List<SpanData> clientSpans = trace.stream()
                .filter(span -> span.getKind() == SpanKind.CLIENT).toList();
        Set<String> providerSpanIds = providerSpans.stream()
                .map(SpanData::getSpanId)
                .collect(Collectors.toSet());

        assertThat(providerSpans).hasSize(2)
                .allSatisfy(span -> assertThat(span.getParentSpanId()).isEqualTo(root.context().spanId()));
        assertThat(clientSpans).hasSize(2)
                .allSatisfy(span -> assertThat(providerSpanIds).contains(span.getParentSpanId()));
        assertThat(clientSpans).extracting(SpanData::getSpanId)
                .containsExactlyInAnyOrder(prometheusClientSpanId, lokiClientSpanId);
    }

    private static String assertTraceparent(String traceparent, Span root) {
        assertThat(traceparent).isNotBlank();
        String[] parts = traceparent.split("-");
        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isEqualTo("00");
        assertThat(parts[1]).isEqualTo(root.context().traceId()).hasSize(32);
        assertThat(parts[2]).hasSize(16).isNotEqualTo(root.context().spanId())
                .isNotEqualTo("0000000000000000");
        assertThat(parts[3]).isEqualTo("01");
        return parts[2];
    }

    private static HttpServer startDownstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/query", exchange -> respond(exchange, PROMETHEUS_TRACEPARENT, """
                    {"status":"success","data":{"resultType":"vector","result":[]}}
                    """));
            server.createContext("/loki/api/v1/query_range", exchange -> respond(exchange, LOKI_TRACEPARENT, """
                    {"status":"success","data":{"resultType":"streams","result":[]}}
                    """));
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start tracing test downstream", exception);
        }
    }

    private static void respond(HttpExchange exchange, AtomicReference<String> captured, String response)
            throws IOException {
        captured.set(exchange.getRequestHeaders().getFirst("traceparent"));
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExporterConfig {
        @Bean
        SpanExporter spanExporter() {
            return EXPORTER;
        }
    }

    private static final class CapturingSpanExporter implements SpanExporter {
        private final List<SpanData> spans = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> items) {
            spans.addAll(items);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        void clear() {
            spans.clear();
        }

        List<SpanData> spans(String traceId) {
            return spans.stream().filter(span -> traceId.equals(span.getTraceId())).toList();
        }
    }
}
