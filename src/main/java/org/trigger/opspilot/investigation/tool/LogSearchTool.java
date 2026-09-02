package org.trigger.opspilot.investigation.tool;

import org.springframework.stereotype.Component;
import org.trigger.opspilot.incident.IncidentService;
import org.trigger.opspilot.observability.ObservationQueryContextFactory;
import org.trigger.opspilot.observability.ObservationQueryContextFactory.ObservationQueryContext;
import org.trigger.opspilot.observability.logs.LogsProvider;
import org.trigger.opspilot.observability.logs.LogsProviderRouter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LogSearchTool implements InvestigationTool {
    private final ObservationQueryContextFactory contextFactory;
    private final LogsProviderRouter providerRouter;

    public LogSearchTool(ObservationQueryContextFactory contextFactory,
                         LogsProviderRouter providerRouter) {
        this.contextFactory = contextFactory;
        this.providerRouter = providerRouter;
    }

    @Override
    public int order() {
        return 35;
    }

    @Override
    public String name() {
        return "log_search";
    }

    @Override
    public String title() {
        return "检索关联资源日志";
    }

    @Override
    public ToolResult execute(IncidentService.IncidentDetail incident) {
        ObservationQueryContext context = contextFactory.from(incident);
        LogsProvider.LogsResult result = providerRouter.query(new LogsProvider.LogsQuery(
                context.incidentId(), context.resourceId(), context.resourceCode(), context.resourceName(),
                context.start(), context.end(), 30));
        List<ToolEvidence> evidence = result.entries().stream()
                .map(entry -> new ToolEvidence(
                        "LOG", "log:" + result.providerId() + ":" + entry.ref(), entry.occurredAt(),
                        "[" + entry.level() + "] " + entry.resourceCode() + "/" + entry.loggerName()
                                + "；trace=" + (entry.traceId() == null ? "-" : entry.traceId())
                                + "；" + entry.message() + "；metadata=" + entry.metadata()))
                .toList();
        String warning = result.warnings().isEmpty() ? "" : "；降级信息=" + String.join(", ", result.warnings());
        String summary = evidence.isEmpty()
                ? "Provider " + result.providerId() + " 未返回故障窗口日志" + warning
                : "Provider " + result.providerId() + " 返回 " + evidence.size() + " 条日志证据，脱敏字段 "
                        + result.redactedFields() + " 个" + warning;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", result.providerId());
        metadata.put("query", result.query());
        metadata.put("externalRef", result.externalRef());
        metadata.put("windowStart", context.start());
        metadata.put("windowEnd", context.end());
        metadata.put("limit", 30);
        metadata.put("redactedFields", result.redactedFields());
        metadata.put("warnings", result.warnings());
        return new ToolResult(summary, evidence, Map.copyOf(metadata));
    }
}
