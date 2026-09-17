package org.trigger.opspilot.observability.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;
import org.trigger.opspilot.alert.AlertService;
import org.trigger.opspilot.investigation.InvestigationService;
import org.trigger.opspilot.investigation.tool.InvestigationTool;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class OpsPilotTracingTest {
    private final SimpleTracer tracer = new SimpleTracer();
    private final OpsPilotTracing tracing = OpsPilotTracing.using(tracer);

    @Test
    void shouldRunCapturedTaskWithoutAParentSpan() {
        AtomicBoolean executed = new AtomicBoolean();

        tracing.capture(() -> executed.set(true)).run();

        assertThat(executed).isTrue();
    }

    @Test
    void shouldPreserveTraceHierarchyAcrossCapturedThreadAndTagBusinessIdentifiers() throws Exception {
        InvestigationService.PreparedRun prepared = new InvestigationService.PreparedRun(
                71, 19, "INCIDENT_WORKSPACE", "trace-test", LocalDateTime.now().plusMinutes(1),
                false, "QUEUED");
        InvestigationTool tool = tool("metrics_snapshot");
        Span root = tracer.nextSpan().name("http.request").start();
        Runnable captured;
        try (Tracer.SpanInScope ignored = tracer.withSpan(root)) {
            captured = tracing.capture(() -> tracing.traceAgentRun(prepared, () -> {
                tracing.traceAgentTool(71, 19, tool, () -> {
                    tracing.traceProviderQuery("metrics", "local-metrics", () -> "ok");
                    return new InvestigationTool.ToolResult("sample", List.of(
                            new InvestigationTool.ToolEvidence(
                                    "METRIC", "metric:1", LocalDateTime.now(), "P95=120ms")));
                });
                return new InvestigationService.InvestigationResult(
                        71L, 81L, "COMPLETED", "AGENT_TOOLCHAIN", "summary", "hypothesis",
                        BigDecimal.ONE, "observe", List.of(), List.of());
            }));
        } finally {
            root.end();
        }

        Thread worker = new Thread(captured, "trace-test-worker");
        worker.start();
        worker.join(5_000);
        assertThat(worker.isAlive()).isFalse();

        SimpleSpan run = span("opspilot.agent.run");
        SimpleSpan toolSpan = span("opspilot.agent.tool");
        SimpleSpan provider = span("opspilot.provider.query");
        assertThat(run.getTraceId()).isEqualTo(root.context().traceId());
        assertThat(run.getParentId()).isEqualTo(root.context().spanId());
        assertThat(toolSpan.getParentId()).isEqualTo(run.getSpanId());
        assertThat(provider.getParentId()).isEqualTo(toolSpan.getSpanId());
        assertThat(run.getTags()).containsEntry("opspilot.agent.run.id", "71")
                .containsEntry("opspilot.agent.status", "COMPLETED");
        assertThat(toolSpan.getTags()).containsEntry("opspilot.agent.tool", "metrics_snapshot")
                .containsEntry("opspilot.agent.tool.evidence.count", "1");
        assertThat(provider.getTags()).containsEntry("opspilot.provider.id", "local-metrics")
                .containsEntry("opspilot.provider.outcome", "SUCCEEDED");
    }

    @Test
    void shouldTraceAlertResultWithoutTaggingPayloadText() {
        AlertService.IntakeRequest request = new AlertService.IntakeRequest(
                "prometheus", "external-1", "settlement-api", "P1", "FIRING",
                "sensitive title", "token=secret", Map.of("authorization", "secret"), LocalDateTime.now());

        tracing.traceAlertIntake(request, () -> new AlertService.IntakeResult(
                "CREATED", 101L, 201L, "fingerprint", "created"));

        SimpleSpan span = span("opspilot.alert.intake");
        assertThat(span.getTags()).containsEntry("opspilot.alert.source", "prometheus")
                .containsEntry("opspilot.alert.severity", "P1")
                .containsEntry("opspilot.alert.id", "101")
                .containsEntry("opspilot.incident.id", "201");
        assertThat(span.getTags().toString()).doesNotContain("sensitive title", "token=secret",
                "authorization");
    }

    @Test
    void shouldMarkFailedProviderSpan() {
        RuntimeException failure = new IllegalStateException("token=secret query={credential}");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        tracing.traceProviderQuery("logs", "loki", () -> {
                            throw failure;
                        }))
                .isSameAs(failure);

        SimpleSpan span = span("opspilot.provider.query");
        assertThat(span.getTags()).containsEntry("opspilot.provider.outcome", "FAILED")
                .containsEntry("opspilot.error.type", "IllegalStateException");
        assertThat(span.getTags().toString()).doesNotContain("token=secret", "credential");
        assertThat(span.getError()).isNull();
    }

    private SimpleSpan span(String name) {
        return tracer.getSpans().stream().filter(item -> name.equals(item.getName()))
                .findFirst().orElseThrow();
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
}
