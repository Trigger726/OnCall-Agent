package org.trigger.opspilot.investigation.tool;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.trigger.opspilot.incident.IncidentService;

import java.util.ArrayList;
import java.util.List;

@Component
public class CmdbTopologyTool implements InvestigationTool {
    private final JdbcClient jdbcClient;

    public CmdbTopologyTool(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public String name() {
        return "cmdb_topology";
    }

    @Override
    public String title() {
        return "查询 CMDB 依赖拓扑";
    }

    @Override
    public ToolResult execute(IncidentService.IncidentDetail incident) {
        long resourceId = incident.incident().resourceId();
        List<ToolEvidence> evidence = new ArrayList<>();
        evidence.addAll(jdbcClient.sql("""
                        SELECT rel.id, rel.relation_type, dst.name AS peer_name,
                               dst.status, dst.resource_type
                        FROM cmdb_relation rel
                        JOIN cmdb_resource dst ON dst.id = rel.target_resource_id
                        WHERE rel.source_resource_id = :resourceId ORDER BY rel.id
                        """)
                .param("resourceId", resourceId)
                .query((rs, rowNum) -> new ToolEvidence(
                        "DEPENDENCY", "relation:" + rs.getLong("id"), incident.incident().updatedAt(),
                        "下游 " + rs.getString("relation_type") + " " + rs.getString("peer_name")
                                + "（" + rs.getString("resource_type") + "），状态=" + rs.getString("status")))
                .list());
        evidence.addAll(jdbcClient.sql("""
                        SELECT rel.id, rel.relation_type, src.name AS peer_name,
                               src.status, src.resource_type
                        FROM cmdb_relation rel
                        JOIN cmdb_resource src ON src.id = rel.source_resource_id
                        WHERE rel.target_resource_id = :resourceId ORDER BY rel.id
                        """)
                .param("resourceId", resourceId)
                .query((rs, rowNum) -> new ToolEvidence(
                        "DEPENDENCY", "relation:" + rs.getLong("id"), incident.incident().updatedAt(),
                        "上游 " + rs.getString("relation_type") + " " + rs.getString("peer_name")
                                + "（" + rs.getString("resource_type") + "），状态=" + rs.getString("status")))
                .list());
        String summary = evidence.isEmpty()
                ? "CMDB 中未登记该资源的上下游关系，拓扑证据存在缺口。"
                : "发现 " + evidence.size() + " 条上下游关系，已标记依赖方向和资源状态。";
        return new ToolResult(summary, List.copyOf(evidence));
    }
}
