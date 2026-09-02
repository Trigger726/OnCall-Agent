package org.trigger.opspilot.investigation.tool;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.trigger.opspilot.incident.IncidentService;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class RecentChangeTool implements InvestigationTool {
    private final JdbcClient jdbcClient;

    public RecentChangeTool(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public String name() {
        return "recent_change_correlation";
    }

    @Override
    public String title() {
        return "关联故障窗口内变更";
    }

    @Override
    public ToolResult execute(IncidentService.IncidentDetail incident) {
        long resourceId = incident.incident().resourceId();
        LocalDateTime start = incident.incident().createdAt().minusHours(4);
        LocalDateTime end = incident.incident().updatedAt().plusHours(1);
        List<ToolEvidence> evidence = jdbcClient.sql("""
                        SELECT DISTINCT c.id, c.change_code, c.summary, c.status, c.started_at,
                               r.name AS resource_name
                        FROM change_record c
                        JOIN cmdb_resource r ON r.id = c.resource_id
                        WHERE (c.resource_id = :resourceId
                           OR c.resource_id IN (
                               SELECT target_resource_id FROM cmdb_relation WHERE source_resource_id = :resourceId
                           )
                           OR c.resource_id IN (
                               SELECT source_resource_id FROM cmdb_relation WHERE target_resource_id = :resourceId
                           ))
                          AND c.started_at BETWEEN :start AND :end
                        ORDER BY c.started_at DESC
                        """)
                .param("resourceId", resourceId).param("start", start).param("end", end)
                .query((rs, rowNum) -> new ToolEvidence(
                        "CHANGE", "change:" + rs.getLong("id"),
                        rs.getObject("started_at", LocalDateTime.class),
                        rs.getString("change_code") + "；" + rs.getString("resource_name") + "；"
                                + rs.getString("summary") + "；状态=" + rs.getString("status")))
                .list();
        String summary = evidence.isEmpty()
                ? "故障窗口前 4 小时至最近更新时间后 1 小时内未发现关联资源变更。"
                : "命中 " + evidence.size() + " 条故障窗口变更，仅建立时间相关性，仍需指标或回滚验证因果。";
        return new ToolResult(summary, evidence);
    }
}
