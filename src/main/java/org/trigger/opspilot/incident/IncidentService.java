package org.trigger.opspilot.incident;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.common.PageResponse;
import org.trigger.opspilot.investigation.AgentRunQueryService;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class IncidentService {
    private final JdbcClient jdbcClient;
    private final AuditService auditService;
    private final AgentRunQueryService agentRunQueryService;

    public IncidentService(JdbcClient jdbcClient, AuditService auditService,
                           AgentRunQueryService agentRunQueryService) {
        this.jdbcClient = jdbcClient;
        this.auditService = auditService;
        this.agentRunQueryService = agentRunQueryService;
    }

    public PageResponse<IncidentSummary> list(String status, String severity, int page, int size) {
        String normalizedStatus = status == null ? "" : status.toUpperCase();
        String normalizedSeverity = severity == null ? "" : severity.toUpperCase();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String where = "WHERE (:status = '' OR i.status = :status) AND (:severity = '' OR i.severity = :severity)";
        long total = jdbcClient.sql("SELECT COUNT(*) FROM incident i " + where)
                .param("status", normalizedStatus).param("severity", normalizedSeverity)
                .query(Long.class).single();
        List<IncidentSummary> items = jdbcClient.sql("""
                        SELECT i.id, i.incident_code, i.title, i.severity, i.status, i.version,
                               r.id AS resource_id, r.name AS resource_name,
                               commander.display_name AS commander_name, assignee.display_name AS assignee_name,
                               (SELECT COUNT(*) FROM alert_event a WHERE a.incident_id = i.id) AS alert_count,
                               i.created_at, i.updated_at
                        FROM incident i
                        JOIN cmdb_resource r ON r.id = i.service_resource_id
                        LEFT JOIN sys_user commander ON commander.id = i.commander_id
                        LEFT JOIN sys_user assignee ON assignee.id = i.assignee_id
                        """ + where + " ORDER BY CASE i.severity WHEN 'P1' THEN 1 WHEN 'P2' THEN 2 WHEN 'P3' THEN 3 ELSE 4 END, i.updated_at DESC LIMIT :limit OFFSET :offset")
                .param("status", normalizedStatus).param("severity", normalizedSeverity)
                .param("limit", safeSize).param("offset", (safePage - 1) * safeSize)
                .query(IncidentService::mapSummary).list();
        return new PageResponse<>(items, total, safePage, safeSize);
    }

    public IncidentDetail get(long id) {
        IncidentSummary summary = findSummary(id);
        String description = jdbcClient.sql("SELECT description FROM incident WHERE id = :id")
                .param("id", id).query(String.class).optional().orElse("");
        List<AlertView> alerts = jdbcClient.sql("""
                        SELECT id, source, severity, status, title, occurrence_count, first_occurred_at, last_occurred_at
                        FROM alert_event WHERE incident_id = :id ORDER BY last_occurred_at DESC
                        """)
                .param("id", id).query((rs, rowNum) -> new AlertView(
                        rs.getLong("id"), rs.getString("source"), rs.getString("severity"),
                        rs.getString("status"), rs.getString("title"), rs.getInt("occurrence_count"),
                        rs.getObject("first_occurred_at", LocalDateTime.class),
                        rs.getObject("last_occurred_at", LocalDateTime.class))).list();
        List<TimelineView> timeline = jdbcClient.sql("""
                        SELECT t.id, t.event_type, t.from_status, t.to_status, t.content, t.evidence_ref,
                               u.display_name AS actor_name, t.created_at
                        FROM incident_timeline t LEFT JOIN sys_user u ON u.id = t.actor_id
                        WHERE t.incident_id = :id ORDER BY t.created_at DESC, t.id DESC
                        """)
                .param("id", id).query((rs, rowNum) -> new TimelineView(
                        rs.getLong("id"), rs.getString("event_type"), rs.getString("from_status"),
                        rs.getString("to_status"), rs.getString("actor_name"), rs.getString("content"),
                        rs.getString("evidence_ref"), rs.getObject("created_at", LocalDateTime.class))).list();
        List<InvestigationView> reports = jdbcClient.sql("""
                        SELECT id, engine, status, summary, hypothesis, confidence, suggestions, evidence_json, created_at
                        FROM investigation_report WHERE incident_id = :id ORDER BY created_at DESC
                        """)
                .param("id", id).query((rs, rowNum) -> new InvestigationView(
                        rs.getLong("id"), rs.getString("engine"), rs.getString("status"),
                        rs.getString("summary"), rs.getString("hypothesis"), rs.getBigDecimal("confidence"),
                        rs.getString("suggestions"), rs.getString("evidence_json"),
                        rs.getObject("created_at", LocalDateTime.class))).list();
        List<AgentRunQueryService.AgentRunView> agentRuns = agentRunQueryService.listByIncident(id);
        return new IncidentDetail(summary, description, alerts, timeline, reports, agentRuns);
    }

    @Transactional
    public IncidentSummary transition(long id, IncidentStatus target, int expectedVersion, String note) {
        IncidentSummary current = findSummary(id);
        IncidentStatus source = IncidentStatus.valueOf(current.status());
        if (!source.canTransitionTo(target)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_INCIDENT_TRANSITION",
                    source + " 不能直接流转到 " + target);
        }
        Long actorId = auditService.currentUserId();
        int updated = jdbcClient.sql("""
                        UPDATE incident SET status = :status, version = version + 1, updated_at = CURRENT_TIMESTAMP,
                            acknowledged_at = CASE WHEN :status = 'ACKNOWLEDGED' THEN CURRENT_TIMESTAMP ELSE acknowledged_at END,
                            resolved_at = CASE WHEN :status = 'RESOLVED' THEN CURRENT_TIMESTAMP ELSE resolved_at END,
                            closed_at = CASE WHEN :status = 'CLOSED' THEN CURRENT_TIMESTAMP ELSE closed_at END
                        WHERE id = :id AND version = :version
                        """)
                .param("status", target.name()).param("id", id).param("version", expectedVersion).update();
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "INCIDENT_VERSION_CONFLICT", "Incident 已被其他人更新，请刷新后重试");
        }
        String content = note == null || note.isBlank() ? "状态流转至 " + target : note.trim();
        addTimeline(id, "STATUS_CHANGED", source.name(), target.name(), actorId, content, null);
        auditService.record("INCIDENT_" + target.name(), "INCIDENT", id, content);
        return findSummary(id);
    }

    @Transactional
    public IncidentSummary assign(long id, long assigneeId, int expectedVersion) {
        String assigneeName = jdbcClient.sql("SELECT display_name FROM sys_user WHERE id = :id AND status = 'ACTIVE'")
                .param("id", assigneeId).query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "ASSIGNEE_NOT_FOUND", "处置人不存在或已停用"));
        int updated = jdbcClient.sql("""
                        UPDATE incident SET assignee_id = :assignee, version = version + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND version = :version
                        """)
                .param("assignee", assigneeId).param("id", id).param("version", expectedVersion).update();
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "INCIDENT_VERSION_CONFLICT", "Incident 已被其他人更新，请刷新后重试");
        }
        addTimeline(id, "ASSIGNED", null, null, auditService.currentUserId(), "处置人变更为 " + assigneeName, null);
        auditService.record("INCIDENT_ASSIGN", "INCIDENT", id, "处置人变更为 " + assigneeName);
        return findSummary(id);
    }

    @Transactional
    public void addNote(long id, String content, String evidenceRef) {
        findSummary(id);
        addTimeline(id, "NOTE", null, null, auditService.currentUserId(), content, evidenceRef);
        auditService.record("INCIDENT_NOTE", "INCIDENT", id, content);
    }

    public IncidentSummary findSummary(long id) {
        return jdbcClient.sql("""
                        SELECT i.id, i.incident_code, i.title, i.severity, i.status, i.version,
                               r.id AS resource_id, r.name AS resource_name,
                               commander.display_name AS commander_name, assignee.display_name AS assignee_name,
                               (SELECT COUNT(*) FROM alert_event a WHERE a.incident_id = i.id) AS alert_count,
                               i.created_at, i.updated_at
                        FROM incident i JOIN cmdb_resource r ON r.id = i.service_resource_id
                        LEFT JOIN sys_user commander ON commander.id = i.commander_id
                        LEFT JOIN sys_user assignee ON assignee.id = i.assignee_id
                        WHERE i.id = :id
                        """)
                .param("id", id).query(IncidentService::mapSummary).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "Incident 不存在"));
    }

    private void addTimeline(long incidentId, String eventType, String fromStatus, String toStatus,
                             Long actorId, String content, String evidenceRef) {
        jdbcClient.sql("""
                        INSERT INTO incident_timeline(incident_id, event_type, from_status, to_status, actor_id, content, evidence_ref)
                        VALUES (:incidentId, :eventType, :fromStatus, :toStatus, :actorId, :content, :evidenceRef)
                        """)
                .param("incidentId", incidentId).param("eventType", eventType)
                .param("fromStatus", fromStatus).param("toStatus", toStatus).param("actorId", actorId)
                .param("content", content).param("evidenceRef", evidenceRef).update();
    }

    private static IncidentSummary mapSummary(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new IncidentSummary(
                rs.getLong("id"), rs.getString("incident_code"), rs.getString("title"),
                rs.getString("severity"), rs.getString("status"), rs.getInt("version"),
                rs.getLong("resource_id"), rs.getString("resource_name"), rs.getString("commander_name"),
                rs.getString("assignee_name"), rs.getInt("alert_count"),
                rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class));
    }

    public record IncidentSummary(Long id, String incidentCode, String title, String severity, String status,
                                  int version, Long resourceId, String resourceName, String commander,
                                  String assignee, int alertCount, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record IncidentDetail(IncidentSummary incident, String description, List<AlertView> alerts,
                                 List<TimelineView> timeline, List<InvestigationView> investigations,
                                 List<AgentRunQueryService.AgentRunView> agentRuns) {
    }

    public record AlertView(Long id, String source, String severity, String status, String title,
                            int occurrenceCount, LocalDateTime firstOccurredAt, LocalDateTime lastOccurredAt) {
    }

    public record TimelineView(Long id, String eventType, String fromStatus, String toStatus, String actor,
                               String content, String evidenceRef, LocalDateTime createdAt) {
    }

    public record InvestigationView(Long id, String engine, String status, String summary, String hypothesis,
                                    java.math.BigDecimal confidence, String suggestions, String evidenceJson,
                                    LocalDateTime createdAt) {
    }
}
