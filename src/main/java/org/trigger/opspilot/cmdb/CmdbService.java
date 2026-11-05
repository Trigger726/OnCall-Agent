package org.trigger.opspilot.cmdb;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.common.PageResponse;

import java.time.LocalDateTime;
import java.util.List;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.security.SessionAuthorization;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.dao.DuplicateKeyException;
import jakarta.validation.constraints.*;
import java.util.Locale;

@Service
public class CmdbService {
    private final JdbcClient jdbcClient;
    private final AuditService audit;
    private final SessionAuthorization authorization;

    public CmdbService(JdbcClient jdbcClient, AuditService audit, SessionAuthorization authorization) {
        this.jdbcClient = jdbcClient;
        this.audit = audit;
        this.authorization = authorization;
    }

    public PageResponse<ResourceSummary> list(String query, String type, String status, int page, int size) {
        String q = query == null ? "" : query.trim();
        if(q.length()>120) throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","搜索内容最多120个字符");
        String normalizedType = type == null ? "" : type.trim().toUpperCase();
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        String where = """
                WHERE (:q = '' OR LOCATE(LOWER(:q), LOWER(r.name)) > 0 OR LOCATE(LOWER(:q), LOWER(r.resource_code)) > 0)
                  AND (:type = '' OR r.resource_type = :type)
                  AND (:status = '' OR r.status = :status)
                """;
        long total = jdbcClient.sql("SELECT COUNT(*) FROM cmdb_resource r " + where)
                .param("q", q)
                .param("type", normalizedType).param("status", normalizedStatus)
                .query(Long.class).single();
        List<ResourceSummary> items = jdbcClient.sql("""
                        SELECT r.id, r.resource_code, r.resource_type, r.name, r.environment, r.status,
                               r.description, u.display_name AS owner_name,
                               (SELECT COUNT(*) FROM incident i WHERE i.service_resource_id = r.id
                                 AND i.status NOT IN ('RESOLVED','CLOSED')) AS active_incidents,
                               r.updated_at, r.owner_user_id, r.version
                        FROM cmdb_resource r LEFT JOIN sys_user u ON u.id = r.owner_user_id
                        """ + where + " ORDER BY active_incidents DESC, r.name, r.id LIMIT :limit OFFSET :offset")
                .param("q", q)
                .param("type", normalizedType).param("status", normalizedStatus)
                .param("limit", safeSize).param("offset", (long)(safePage - 1) * safeSize)
                .query(CmdbService::mapSummary).list();
        return new PageResponse<>(items, total, safePage, safeSize);
    }

    public ResourceDetail get(long id) {
        ResourceSummary resource = jdbcClient.sql("""
                        SELECT r.id, r.resource_code, r.resource_type, r.name, r.environment, r.status,
                               r.description, u.display_name AS owner_name,
                               (SELECT COUNT(*) FROM incident i WHERE i.service_resource_id = r.id
                                 AND i.status NOT IN ('RESOLVED','CLOSED')) AS active_incidents,
                               r.updated_at, r.owner_user_id, r.version
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
                rs.getObject("updated_at", LocalDateTime.class), rs.getObject("owner_user_id", Long.class), rs.getInt("version"));
    }

    public record ResourceSummary(Long id, String resourceCode, String resourceType, String name,
                                  String environment, String status, String description, String ownerName,
                                  int activeIncidents, LocalDateTime updatedAt, Long ownerId, int version) {
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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResourceDetail save(Long id, ResourceCommand command) {
        var lease=authorization.current();
        if(!authorization.authorized(lease,true,true)) throw new CredentialsExpiredException("Session changed");
        String role=jdbcClient.sql("SELECT role_code FROM sys_user WHERE id=:id").param("id",lease.userId()).query(String.class).single();
        if(!List.of("ADMIN","OPS_MANAGER").contains(role)) throw new AccessDeniedException("Resource manager required");
        String code=command.resourceCode().trim().toUpperCase(Locale.ROOT);
        if(id!=null){
            var current=jdbcClient.sql("SELECT resource_code,version FROM cmdb_resource WHERE id=:id FOR UPDATE").param("id",id).query((rs,row)->java.util.Map.<String,Object>of("resource_code",rs.getString(1),"version",rs.getInt(2))).optional()
                    .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","资源不存在"));
            if(command.version()==null || ((Number)current.get("version")).intValue()!=command.version())
                throw new ApiException(HttpStatus.CONFLICT,"RESOURCE_VERSION_CONFLICT","资源已被其他人更新，请重新加载后再编辑");
            if(!code.equals(current.get("resource_code"))) throw new ApiException(HttpStatus.BAD_REQUEST,"RESOURCE_CODE_IMMUTABLE","资源编码用于告警关联，登记后不能修改");
        }
        var owner=jdbcClient.sql("SELECT role_code,status FROM sys_user WHERE id=:id FOR UPDATE").param("id",command.ownerId()).query((rs,row)->java.util.Map.of("role_code",rs.getString(1),"status",rs.getString(2))).optional();
        if(owner.isEmpty() || !"ACTIVE".equals(owner.get().get("status")) || !List.of("ADMIN","OPS_MANAGER","ON_CALL").contains(owner.get().get("role_code")))
            throw new ApiException(HttpStatus.BAD_REQUEST,"RESOURCE_OWNER_INVALID","请选择有效的运维负责人");
        try {
            if(id==null){
                jdbcClient.sql("INSERT INTO cmdb_resource(resource_code,resource_type,name,environment,status,owner_user_id,description,attributes_json) VALUES (:code,:type,:name,:environment,:status,:owner,:description,'{}')")
                        .param("code",code).param("type",command.resourceType()).param("name",command.name().trim()).param("environment",command.environment())
                        .param("status",command.status()).param("owner",command.ownerId()).param("description",command.description().trim()).update();
                id=jdbcClient.sql("SELECT id FROM cmdb_resource WHERE resource_code=:code").param("code",code).query(Long.class).single();
                audit.record("RESOURCE_CREATED","CMDB_RESOURCE",id,"登记资源；version=0");
            }else{
                jdbcClient.sql("UPDATE cmdb_resource SET resource_type=:type,name=:name,environment=:environment,status=:status,owner_user_id=:owner,description=:description,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                        .param("id",id).param("type",command.resourceType()).param("name",command.name().trim()).param("environment",command.environment())
                        .param("status",command.status()).param("owner",command.ownerId()).param("description",command.description().trim()).update();
                audit.record("RESOURCE_UPDATED","CMDB_RESOURCE",id,"维护资源；version="+(command.version()+1));
            }
        }catch(DuplicateKeyException exception){throw new ApiException(HttpStatus.CONFLICT,"RESOURCE_CODE_EXISTS","资源编码已存在，请搜索并维护已有资源");}
        return get(id);
    }

    public record ResourceCommand(
            @NotBlank @Size(max=64) @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9._-]*") String resourceCode,
            @NotBlank @Pattern(regexp="APPLICATION|API|DATABASE|MIDDLEWARE") String resourceType,
            @NotBlank @Size(max=128) String name,
            @NotBlank @Pattern(regexp="PRODUCTION|STAGING|TEST|DEVELOPMENT") String environment,
            @NotBlank @Pattern(regexp="RUNNING|DEGRADED|MAINTENANCE|OFFLINE") String status,
            @NotNull @Positive Long ownerId,
            @NotNull @Size(max=500) String description,
            @PositiveOrZero Integer version) { }
}
