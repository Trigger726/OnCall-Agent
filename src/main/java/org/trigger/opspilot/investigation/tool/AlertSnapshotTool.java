package org.trigger.opspilot.investigation.tool;

import org.springframework.stereotype.Component;
import org.trigger.opspilot.incident.IncidentService;

import java.util.List;

@Component
public class AlertSnapshotTool implements InvestigationTool {
    @Override
    public int order() {
        return 10;
    }

    @Override
    public String name() {
        return "alert_snapshot";
    }

    @Override
    public String title() {
        return "读取关联告警快照";
    }

    @Override
    public ToolResult execute(IncidentService.IncidentDetail incident) {
        List<ToolEvidence> evidence = incident.alerts().stream()
                .map(alert -> new ToolEvidence(
                        "ALERT", "alert:" + alert.id(), alert.lastOccurredAt(),
                        alert.title() + "；来源=" + alert.source() + "；状态=" + alert.status()
                                + "；累计=" + alert.occurrenceCount()))
                .toList();
        String summary = evidence.isEmpty()
                ? "Incident 暂无关联告警，无法从告警侧建立症状基线。"
                : "读取 " + evidence.size() + " 条关联告警，已形成症状与严重等级快照。";
        return new ToolResult(summary, evidence);
    }
}
