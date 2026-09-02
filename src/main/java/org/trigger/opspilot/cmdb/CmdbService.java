package org.trigger.opspilot.cmdb;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.common.PageResponse;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class CmdbService {
    private final JdbcClient jdbcClient;

    public CmdbService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public PageResponse<ResourceSummary> list(String query, String type, String status, int page, int size) {
        String q = query == null ? "" : query.trim();
        String normalizedType = type == null ? "" : type.trim().toUpperCase();
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        String where = """
                WHERE (:q = '' OR LOWER(r.name) LIKE LOWER(:like) OR LOWER(r.resource_code) LIKE LOWER(:like))
                  AND (:type = '' OR r.resource_type = :type)
                  AND (:status = '' OR r.status = :status)
                """;
        long total = jdbcClient.sql("SELECT COUNT(*) FROM cmdb_resource r " + where)
                .param("q", q).param("like", "%" + q + "%")
                .param("type", normalizedType).param("status", normalizedStatus)
                .query(Long.class).single();
        List<ResourceSummary> items = jdbcClient.sql("""
                        SELECT r.id, r.resource_code, r.resource_type, r.name, r.environment, r.status,
                               r.description, u.display_name AS owner_name,
                               (SELECT COUNT(*) FROM incident i WHERE i.service_resource_id = r.id
                                 AND i.status NOT IN ('RESOLVED','CLOSED')) AS active_incidents,
                               r.updated_at
                        FROM cmdb_resource r LEFT JOIN sys_user u ON u.id = r.owner_user_id
                        """ + where + " ORDER BY active_incidents DESC, r.name LIMIT :limit OFFSET :offset")
                .param("q", q).param("like", "%" + q + "%")
                .param("type", normalizedType).param("status", normalizedStatus)
                .param("limit", safeSize).param("offset", (safePage - 1) * safeSize)
                .query(CmdbService::mapSummary).list();
        return new PageResponse<>(items, total, safePage, safeSize);
    }

    public ResourceDetail get(long id) {
        ResourceSummary resource = jdbcClient.sql("""
                        SELECT r.id, r.resource_code, r.resource_type, r.name, r.environment, r.status,
                               r.description, u.display_name AS owner_name,
                               (SELECT COUNT(*) FROM incident i WHERE i.service_resource_id = r.id
                                 AND i.status NOT IN ('RESOLVED','CLOSED')) AS active_incidents,
                               r.updated_at
                        FROM cmdb_resource r LEFT JOIN sys_user u ON u.id = r.owner_user_id
                        WHERE r.id = :id
                        """)
                .param("id", id).query(CmdbService::mapSummary).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在"));
        String attributes = jdbcClient.sql("SELECT attributes_json FROM cmdb_resource WHERE id = :id")
                .param("id", id).query(String.class).optional().orElse("{}");
        List<RelationView> relations = jdbcClient.sql("""
                        SELECT rel.id, rel.relation_type, rel.source_resource_id, src.name AS source_name,
                               rel.target_resource_id, dst.name AS target_name
                        FROM cmdb_relation rel
                        JOIN cmdb_resource src ON src.id = rel.source_resource_id
                        JOIN cmdb_resource dst ON dst.id = rel.target_resource_id
                        WHERE rel.source_resource_id = :id OR rel.target_resource_id = :id
                        ORDER BY rel.id
                        """)
                .param("id", id)
                .query((rs, rowNum) -> new RelationView(
                        rs.getLong("id"), rs.getString("relation_type"),
                        rs.getLong("source_resource_id"), rs.getString("source_name"),
                        rs.getLong("target_resource_id"), rs.getString("target_name")))
                .list();
        List<ChangeView> changes = jdbcClient.sql("""
                        SELECT c.id, c.change_code, c.change_type, c.summary, c.status, u.display_name,
                               c.started_at, c.finished_at
                        FROM change_record c LEFT JOIN sys_user u ON u.id = c.operator_id
                        WHERE c.resource_id = :id ORDER BY c.started_at DESC LIMIT 20
                        """)
                .param("id", id)
                .query((rs, rowNum) -> new ChangeView(
                        rs.getLong("id"), rs.getString("change_code"), rs.getString("change_type"),
                        rs.getString("summary"), rs.getString("status"), rs.getString("display_name"),
                        rs.getObject("started_at", LocalDateTime.class),
                        rs.getObject("finished_at", LocalDateTime.class)))
                .list();
        return new ResourceDetail(resource, attributes, relations, changes);
    }

    public Topology topology() {
        List<TopologyNode> nodes = jdbcClient.sql("""
                        SELECT id, name, resource_type, status, environment FROM cmdb_resource ORDER BY id
                        """)
                .query((rs, rowNum) -> new TopologyNode(
                        rs.getLong("id"), rs.getString("name"), rs.getString("resource_type"),
                        rs.getString("status"), rs.getString("environment")))
                .list();
        List<TopologyEdge> edges = jdbcClient.sql("""
                        SELECT id, source_resource_id, target_resource_id, relation_type FROM cmdb_relation ORDER BY id
                        """)
                .query((rs, rowNum) -> new TopologyEdge(
                        rs.getLong("id"), rs.getLong("source_resource_id"),
                        rs.getLong("target_resource_id"), rs.getString("relation_type")))
                .list();
        return new Topology(nodes, edges);
    }

    private static ResourceSummary mapSummary(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ResourceSummary(
                rs.getLong("id"), rs.getString("resource_code"), rs.getString("resource_type"),
                rs.getString("name"), rs.getString("environment"), rs.getString("status"),
                rs.getString("description"), rs.getString("owner_name"), rs.getInt("active_incidents"),
                rs.getObject("updated_at", LocalDateTime.class));
    }

    public record ResourceSummary(Long id, String resourceCode, String resourceType, String name,
                                  String environment, String status, String description, String ownerName,
                                  int activeIncidents, LocalDateTime updatedAt) {
    }

    public record ResourceDetail(ResourceSummary resource, String attributesJson,
                                 List<RelationView> relations, List<ChangeView> recentChanges) {
    }

    public record RelationView(Long id, String relationType, Long sourceId, String sourceName,
                               Long targetId, String targetName) {
    }

    public record ChangeView(Long id, String changeCode, String changeType, String summary, String status,
                             String operator, LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    public record Topology(List<TopologyNode> nodes, List<TopologyEdge> edges) {
    }

    public record TopologyNode(Long id, String name, String type, String status, String environment) {
    }

    public record TopologyEdge(Long id, Long source, Long target, String type) {
    }
}
