package org.trigger.opspilot.observability.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.trigger.opspilot.investigation.InvestigationService;
import org.trigger.opspilot.investigation.tool.InvestigationTool;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class OpenTelemetryBridgeTest {

    @Test
    void shouldExportCapturedAsyncHierarchyThroughRealOtelBridge() throws Exception {
        CapturingExporter exporter = new CapturingExporter();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()) {
            OtelCurrentTraceContext currentTraceContext = new OtelCurrentTraceContext();
            Tracer tracer = new OtelTracer(provider.get("opspilot-test"), currentTraceContext, event -> { });
            OpsPilotTracing tracing = OpsPilotTracing.using(tracer);
            InvestigationService.PreparedRun prepared = new InvestigationService.PreparedRun(
                    91, 29, "OTEL_BRIDGE_TEST", "otel-test", LocalDateTime.now().plusMinutes(1),
                    false, "QUEUED");
            InvestigationTool tool = tool("log_search");

            Span root = tracer.nextSpan().name("http.request").start();
            Runnable captured;
            try (Tracer.SpanInScope ignored = tracer.withSpan(root)) {
                captured = tracing.capture(() -> tracing.traceAgentRun(prepared, () -> {
                    tracing.traceAgentTool(91, 29, tool, () -> {
                        tracing.traceProviderQuery("logs", "loki", () -> "ok");
                        return new InvestigationTool.ToolResult("sample", List.of());
                    });
                    return new InvestigationService.InvestigationResult(
                            91L, 101L, "COMPLETED", "AGENT_TOOLCHAIN", "summary", "hypothesis",
                            BigDecimal.ONE, "observe", List.of(), List.of());
                }));
            } finally {
                root.end();
            }

            Thread worker = new Thread(captured, "otel-bridge-worker");
            worker.start();
            worker.join(5_000);
            assertThat(worker.isAlive()).isFalse();

            SpanData rootData = exporter.span("http.request");
            SpanData run = exporter.span("opspilot.agent.run");
            SpanData toolSpan = exporter.span("opspilot.agent.tool");
            SpanData providerSpan = exporter.span("opspilot.provider.query");
            assertThat(run.getTraceId()).isEqualTo(rootData.getTraceId());
            assertThat(run.getParentSpanId()).isEqualTo(rootData.getSpanId());
            assertThat(toolSpan.getParentSpanId()).isEqualTo(run.getSpanId());
            assertThat(providerSpan.getParentSpanId()).isEqualTo(toolSpan.getSpanId());
            assertThat(run.getAttributes().get(AttributeKey.stringKey("opspilot.agent.run.id")))
                    .isEqualTo("91");
            assertThat(providerSpan.getAttributes().get(AttributeKey.stringKey("opspilot.provider.id")))
                    .isEqualTo("loki");
        }
    }

    private static InvestigationTool tool(String name) {
        return new InvestigationTool() {
            @Override public int order() { return 1; }
            @Override public String name() { return name; }
            @Override public String title() { return "test"; }
            @Override public ToolResult execute(org.trigger.opspilot.incident.IncidentService.IncidentDetail incident) {
                return new ToolResult("unused", List.of());
            }
        };
    }

    private static final class CapturingExporter implements SpanExporter {
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

        SpanData span(String name) {
            return spans.stream().filter(item -> name.equals(item.getName())).findFirst().orElseThrow();
        }
    }
}
