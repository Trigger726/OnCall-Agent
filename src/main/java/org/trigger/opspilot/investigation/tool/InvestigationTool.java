package org.trigger.opspilot.investigation.tool;

import org.trigger.opspilot.incident.IncidentService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** A read-only evidence source used by the investigation agent. */
public interface InvestigationTool {
    int order();

    String name();

    String title();

    ToolResult execute(IncidentService.IncidentDetail incident);

    default ToolResult execute(IncidentService.IncidentDetail incident, ToolContext context) {
        return execute(incident);
    }

    record ToolContext(Long actorId) {
    }

    record ToolResult(String summary, List<ToolEvidence> evidence, Map<String, Object> traceMetadata) {
        public ToolResult(String summary, List<ToolEvidence> evidence) {
            this(summary, evidence, Map.of());
        }
    }

    record ToolEvidence(String type, String ref, LocalDateTime observedAt, String text) {
    }
}
