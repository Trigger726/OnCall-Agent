package org.trigger.opspilot.investigation.tool;

import org.springframework.stereotype.Component;
import org.trigger.opspilot.incident.IncidentService;
import org.trigger.opspilot.observability.ObservationQueryContextFactory;
import org.trigger.opspilot.observability.ObservationQueryContextFactory.ObservationQueryContext;
import org.trigger.opspilot.observability.metrics.MetricsProvider;
import org.trigger.opspilot.observability.metrics.MetricsProviderRouter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MetricsSnapshotTool implements InvestigationTool {
    private final ObservationQueryContextFactory contextFactory;
    private final MetricsProviderRouter providerRouter;

    public MetricsSnapshotTool(ObservationQueryContextFactory contextFactory,
                               MetricsProviderRouter providerRouter) {
        this.contextFactory = contextFactory;
        this.providerRouter = providerRouter;
    }

    @Override
    public int order() {
        return 25;
    }

    @Override
    public String name() {
        return "metrics_snapshot";
    }

    @Override
    public String title() {
        return "查询故障窗口指标";
    }

    @Override
    public ToolResult execute(IncidentService.IncidentDetail incident) {
        ObservationQueryContext context = contextFactory.from(incident);
        MetricsProvider.MetricsResult result = providerRouter.query(new MetricsProvider.MetricsQuery(
                context.incidentId(), context.resourceId(), context.resourceCode(), context.resourceName(),
                context.start(), context.end()));
        List<ToolEvidence> evidence = result.samples().stream()
                .map(sample -> new ToolEvidence(
                        "METRIC", "metric:" + result.providerId() + ":" + sample.ref(), sample.observedAt(),
                        sample.resourceCode() + "；" + sample.metricName() + "=" + sample.value() + " "
                                + sample.unit() + "；labels=" + sample.labels()))
                .toList();
        String warning = result.warnings().isEmpty() ? "" : "；降级信息=" + String.join(", ", result.warnings());
        String summary = evidence.isEmpty()
                ? "Provider " + result.providerId() + " 未返回故障窗口指标" + warning
                : "Provider " + result.providerId() + " 返回 " + evidence.size() + " 条指标证据" + warning;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", result.providerId());
        metadata.put("query", result.query());
        metadata.put("externalRef", result.externalRef());
        metadata.put("windowStart", context.start());
        metadata.put("windowEnd", context.end());
        metadata.put("warnings", result.warnings());
        return new ToolResult(summary, evidence, Map.copyOf(metadata));
    }
}
