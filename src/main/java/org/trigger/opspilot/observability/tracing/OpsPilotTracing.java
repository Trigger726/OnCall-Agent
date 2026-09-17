package org.trigger.opspilot.observability.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.trigger.opspilot.alert.AlertService;
import org.trigger.opspilot.investigation.InvestigationService;
import org.trigger.opspilot.investigation.tool.InvestigationTool;

import java.util.function.Supplier;

@Component
public class OpsPilotTracing {
    private final Tracer tracer;

    @Autowired
    public OpsPilotTracing(ObjectProvider<Tracer> tracerProvider) {
        this.tracer = tracerProvider.getIfAvailable(() -> Tracer.NOOP);
    }

    private OpsPilotTracing(Tracer tracer) {
        this.tracer = tracer;
    }

    public static OpsPilotTracing using(Tracer tracer) {
        return new OpsPilotTracing(tracer);
    }

    public Runnable capture(Runnable task) {
        Span parent = tracer.currentSpan();
        if (parent == null) return task;
        return () -> {
            try (Tracer.SpanInScope ignored = tracer.withSpan(parent)) {
                task.run();
            }
        };
    }

    public AlertService.IntakeResult traceAlertIntake(
            AlertService.IntakeRequest request,
            Supplier<AlertService.IntakeResult> action) {
        Span span = start("opspilot.alert.intake");
        tag(span, "opspilot.alert.source", request.source());
        tag(span, "opspilot.alert.severity", request.severity());
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            AlertService.IntakeResult result = action.get();
            span.tag("opspilot.alert.action", result.action());
            tag(span, "opspilot.alert.id", result.alertId());
            tag(span, "opspilot.incident.id", result.incidentId());
            return result;
        } catch (RuntimeException | Error exception) {
            tagErrorType(span, exception);
            throw exception;
        } finally {
            span.end();
        }
    }

    public InvestigationService.InvestigationResult traceAgentRun(
            InvestigationService.PreparedRun run,
            Supplier<InvestigationService.InvestigationResult> action) {
        Span span = start("opspilot.agent.run");
        tag(span, "opspilot.agent.run.id", run.runId());
        tag(span, "opspilot.incident.id", run.incidentId());
        tag(span, "opspilot.agent.trigger", run.triggerSource());
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            InvestigationService.InvestigationResult result = action.get();
            span.tag("opspilot.agent.status", result.status());
            tag(span, "opspilot.agent.report.id", result.reportId());
            return result;
        } catch (RuntimeException | Error exception) {
            tagErrorType(span, exception);
            throw exception;
        } finally {
            span.end();
        }
    }

    public InvestigationTool.ToolResult traceAgentTool(
            long runId, long incidentId, InvestigationTool tool,
            Supplier<InvestigationTool.ToolResult> action) {
        Span span = start("opspilot.agent.tool");
        tag(span, "opspilot.agent.run.id", runId);
        tag(span, "opspilot.incident.id", incidentId);
        tag(span, "opspilot.agent.tool", tool.name());
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            InvestigationTool.ToolResult result = action.get();
            span.tag("opspilot.agent.tool.status", result.evidence().isEmpty() ? "NO_DATA" : "SUCCEEDED");
            span.tag("opspilot.agent.tool.evidence.count", String.valueOf(result.evidence().size()));
            return result;
        } catch (RuntimeException | Error exception) {
            span.tag("opspilot.agent.tool.status", "FAILED");
            tagErrorType(span, exception);
            throw exception;
        } finally {
            span.end();
        }
    }

    public <T> T traceProviderQuery(String kind, String providerId, Supplier<T> action) {
        Span span = start("opspilot.provider.query");
        tag(span, "opspilot.provider.kind", kind);
        tag(span, "opspilot.provider.id", providerId);
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            T result = action.get();
            span.tag("opspilot.provider.outcome", "SUCCEEDED");
            return result;
        } catch (RuntimeException | Error exception) {
            span.tag("opspilot.provider.outcome", "FAILED");
            tagErrorType(span, exception);
            throw exception;
        } finally {
            span.end();
        }
    }

    private Span start(String name) {
        return tracer.nextSpan().name(name).start();
    }

    private static void tag(Span span, String key, Object value) {
        if (value != null) span.tag(key, String.valueOf(value));
    }

    private static void tagErrorType(Span span, Throwable exception) {
        span.tag("opspilot.error.type", exception.getClass().getSimpleName());
    }
}
