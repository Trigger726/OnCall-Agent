package org.trigger.opspilot.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.common.PageResponse;
import org.trigger.opspilot.problem.ProblemService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class AlertService {
    private static final DateTimeFormatter CODE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final ProblemService problemService;

    public AlertService(JdbcClient jdbcClient, ObjectMapper objectMapper,
                        ProblemService problemService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.problemService = problemService;
    }

    public PageResponse<AlertView> list(String status, String severity, int page, int size) {
        String normalizedStatus = status == null ? "" : status.toUpperCase();
        String normalizedSeverity = severity == null ? "" : severity.toUpperCase();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String where = "WHERE (:status = '' OR a.status = :status) AND (:severity = '' OR a.severity = :severity)";
        long total = jdbcClient.sql("SELECT COUNT(*) FROM alert_event a " + where)
                .param("status", normalizedStatus).param("severity", normalizedSeverity)
                .query(Long.class).single();
        List<AlertView> items = jdbcClient.sql("""
                        SELECT a.id, a.source, a.external_event_id, a.severity, a.status, a.title,
                               a.occurrence_count, a.first_occurred_at, a.last_occurred_at,
                               r.name AS resource_name, i.incident_code, a.incident_id
                        FROM alert_event a JOIN cmdb_resource r ON r.id = a.service_resource_id
                        LEFT JOIN incident i ON i.id = a.incident_id
                        """ + where + " ORDER BY a.last_occurred_at DESC LIMIT :limit OFFSET :offset")
                .param("status", normalizedStatus).param("severity", normalizedSeverity)
                .param("limit", safeSize).param("offset", (safePage - 1) * safeSize)
                .query(AlertService::mapAlert).list();
        return new PageResponse<>(items, total, safePage, safeSize);
    }

    @Transactional
    public IntakeResult intake(IntakeRequest request) {
        LocalDateTime occurredAt = request.occurredAt() == null ? LocalDateTime.now() : request.occurredAt();
        ResourceRef resource = jdbcClient.sql("""
                        SELECT id, name, resource_type FROM cmdb_resource WHERE resource_code = :code
                        """)
                .param("code", request.resourceCode())
                .query((rs, rowNum) -> new ResourceRef(
                        rs.getLong("id"), rs.getString("name"), rs.getString("resource_type")))
                .optional()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "RESOURCE_NOT_FOUND",
                        "resourceCode 未匹配到 CMDB 资源"));
        String fingerprint = fingerprint(request.source(), resource.id(), request.severity(), request.title());
        Optional<ExistingAlert> existing = findExisting(request.source(), request.externalEventId(), fingerprint, occurredAt);
        if (existing.isPresent()) {
            ExistingAlert alert = existing.get();
            jdbcClient.sql("""
                            UPDATE alert_event SET occurrence_count = occurrence_count + 1,
                                last_occurred_at = :occurredAt, status = :status,
                                description = :description, labels_json = :labels,
                                version = version + 1, updated_at = CURRENT_TIMESTAMP
                            WHERE id = :id
                            """)
                    .param("occurredAt", occurredAt).param("status", normalizedStatus(request.status()))
                    .param("description", request.description()).param("labels", json(request.labels()))
                    .param("id", alert.id()).update();
            Long incidentId = alert.incidentId();
            if (incidentId == null && "FIRING".equals(normalizedStatus(request.status()))) {
                incidentId = attachToIncident(resource, request.severity(), request.title());
                jdbcClient.sql("UPDATE alert_event SET incident_id = :incidentId WHERE id = :id")
                        .param("incidentId", incidentId).param("id", alert.id()).update();
            }
            if (incidentId != null) {
                problemService.linkMatchingProblem(alert.id(), incidentId);
            }
            return new IntakeResult("DEDUPLICATED", alert.id(), incidentId, fingerprint,
                    "重复告警已压缩，发生次数已累加");
        }

        jdbcClient.sql("""
                        INSERT INTO alert_event(source, external_event_id, fingerprint, service_resource_id,
                          severity, status, title, description, labels_json, first_occurred_at, last_occurred_at)
                        VALUES (:source, :externalId, :fingerprint, :resourceId, :severity, :status,
                          :title, :description, :labels, :occurredAt, :occurredAt)
                        """)
                .param("source", request.source()).param("externalId", blankToNull(request.externalEventId()))
                .param("fingerprint", fingerprint).param("resourceId", resource.id())
                .param("severity", request.severity().toUpperCase()).param("status", normalizedStatus(request.status()))
                .param("title", request.title()).param("description", request.description())
                .param("labels", json(request.labels())).param("occurredAt", occurredAt).update();
        long alertId = jdbcClient.sql("""
                        SELECT id FROM alert_event WHERE source = :source AND fingerprint = :fingerprint
                        ORDER BY id DESC LIMIT 1
                        """)
                .param("source", request.source()).param("fingerprint", fingerprint)
                .query(Long.class).single();
        Long incidentId = null;
        if ("FIRING".equals(normalizedStatus(request.status()))) {
            incidentId = attachToIncident(resource, request.severity(), request.title());
            jdbcClient.sql("UPDATE alert_event SET incident_id = :incidentId WHERE id = :id")
                    .param("incidentId", incidentId).param("id", alertId).update();
            jdbcClient.sql("""
                            INSERT INTO incident_timeline(incident_id, event_type, content, evidence_ref)
                            VALUES (:incidentId, 'ALERT_ATTACHED', :content, :evidenceRef)
                            """)
                    .param("incidentId", incidentId).param("content", "关联新告警：" + request.title())
                    .param("evidenceRef", "alert:" + alertId).update();
            problemService.linkMatchingProblem(alertId, incidentId);
        }
        return new IntakeResult("CREATED", alertId, incidentId, fingerprint, "新告警已接入并完成聚合");
    }

    private Optional<ExistingAlert> findExisting(String source, String externalId, String fingerprint,
                                                  LocalDateTime occurredAt) {
        if (externalId != null && !externalId.isBlank()) {
            return jdbcClient.sql("""
                            SELECT id, incident_id FROM alert_event
                            WHERE source = :source AND external_event_id = :externalId
                            """)
                    .param("source", source).param("externalId", externalId)
                    .query(AlertService::mapExisting).optional();
        }
        return jdbcClient.sql("""
                        SELECT id, incident_id FROM alert_event
                        WHERE fingerprint = :fingerprint AND status = 'FIRING' AND last_occurred_at >= :windowStart
                        ORDER BY last_occurred_at DESC LIMIT 1
                        """)
                .param("fingerprint", fingerprint).param("windowStart", occurredAt.minusMinutes(30))
                .query(AlertService::mapExisting).optional();
    }

    private Long attachToIncident(ResourceRef resource, String severity, String alertTitle) {
        long serviceId = resolveOwningApplication(resource.id(), resource.type());
        Optional<Long> existing = jdbcClient.sql("""
                        SELECT id FROM incident
                        WHERE service_resource_id = :serviceId AND severity = :severity
                          AND status NOT IN ('RESOLVED','CLOSED')
                          AND created_at >= :windowStart
                        ORDER BY created_at DESC LIMIT 1
                        """)
                .param("serviceId", serviceId).param("severity", severity.toUpperCase())
                .param("windowStart", LocalDateTime.now().minusHours(2))
                .query(Long.class).optional();
        if (existing.isPresent()) {
            jdbcClient.sql("UPDATE incident SET updated_at = CURRENT_TIMESTAMP, version = version + 1 WHERE id = :id")
                    .param("id", existing.get()).update();
            return existing.get();
        }
        String code = "INC-" + LocalDateTime.now().format(CODE_TIME) + "-"
                + ThreadLocalRandom.current().nextInt(100, 1000);
        String serviceName = jdbcClient.sql("SELECT name FROM cmdb_resource WHERE id = :id")
                .param("id", serviceId).query(String.class).single();
        jdbcClient.sql("""
                        INSERT INTO incident(incident_code, title, description, severity, status, service_resource_id)
                        VALUES (:code, :title, :description, :severity, 'OPEN', :serviceId)
                        """)
                .param("code", code).param("title", serviceName + "：" + alertTitle)
                .param("description", "由告警聚合规则自动创建，等待值班人员确认。")
                .param("severity", severity.toUpperCase()).param("serviceId", serviceId).update();
        long incidentId = jdbcClient.sql("SELECT id FROM incident WHERE incident_code = :code")
                .param("code", code).query(Long.class).single();
        jdbcClient.sql("""
                        INSERT INTO incident_timeline(incident_id, event_type, to_status, content)
                        VALUES (:incidentId, 'CREATED', 'OPEN', '系统根据告警聚合规则自动创建 Incident。')
                        """)
                .param("incidentId", incidentId).update();
        notifyCurrentOnCall(incidentId, serviceId, code);
        return incidentId;
    }

    private long resolveOwningApplication(long resourceId, String type) {
        if ("APPLICATION".equals(type) || "API".equals(type)) {
            return resourceId;
        }
        return jdbcClient.sql("""
                        SELECT src.id FROM cmdb_relation rel
                        JOIN cmdb_resource src ON src.id = rel.source_resource_id
                        WHERE rel.target_resource_id = :resourceId AND src.resource_type = 'APPLICATION'
                        ORDER BY src.id LIMIT 1
                        """)
                .param("resourceId", resourceId).query(Long.class).optional().orElse(resourceId);
    }

    private void notifyCurrentOnCall(long incidentId, long serviceId, String code) {
        jdbcClient.sql("""
                        SELECT u.username FROM oncall_shift shift
                        JOIN oncall_schedule schedule ON schedule.id = shift.schedule_id
                        JOIN sys_user u ON u.id = shift.user_id
                        WHERE schedule.service_resource_id = :serviceId AND schedule.active = TRUE
                          AND CURRENT_TIMESTAMP BETWEEN shift.starts_at AND shift.ends_at
                        ORDER BY shift.override_flag DESC, shift.starts_at DESC LIMIT 1
                        """)
                .param("serviceId", serviceId).query(String.class).optional()
                .ifPresent(username -> jdbcClient.sql("""
                                INSERT INTO notification_log(incident_id, channel, recipient, status, message)
                                VALUES (:incidentId, 'IN_APP', :recipient, 'SENT', :message)
                                """)
                        .param("incidentId", incidentId).param("recipient", username)
                        .param("message", code + " 已创建，请及时确认").update());
    }

    private String json(Map<String, String> labels) {
        try {
            return objectMapper.writeValueAsString(labels == null ? Map.of() : labels);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LABELS", "告警标签无法序列化");
        }
    }

    private static String fingerprint(String source, long resourceId, String severity, String title) {
        String raw = source.trim().toLowerCase() + '|' + resourceId + '|' + severity.toUpperCase() + '|'
                + title.trim().toLowerCase().replaceAll("\\s+", " ");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String normalizedStatus(String status) {
        return status == null || status.isBlank() ? "FIRING" : status.toUpperCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ExistingAlert mapExisting(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long incident = rs.getLong("incident_id");
        return new ExistingAlert(rs.getLong("id"), rs.wasNull() ? null : incident);
    }

    private static AlertView mapAlert(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long incidentId = rs.getLong("incident_id");
        return new AlertView(
                rs.getLong("id"), rs.getString("source"), rs.getString("external_event_id"),
                rs.getString("severity"), rs.getString("status"), rs.getString("title"),
                rs.getString("resource_name"), rs.getInt("occurrence_count"),
                rs.getObject("first_occurred_at", LocalDateTime.class),
                rs.getObject("last_occurred_at", LocalDateTime.class),
                rs.wasNull() ? null : incidentId, rs.getString("incident_code"));
    }

    private record ExistingAlert(Long id, Long incidentId) {
    }

    private record ResourceRef(Long id, String name, String type) {
    }

    public record AlertView(Long id, String source, String externalEventId, String severity, String status,
                            String title, String resourceName, int occurrenceCount,
                            LocalDateTime firstOccurredAt, LocalDateTime lastOccurredAt,
                            Long incidentId, String incidentCode) {
    }

    public record IntakeRequest(String source, String externalEventId, String resourceCode, String severity,
                                String status, String title, String description, Map<String, String> labels,
                                LocalDateTime occurredAt) {
    }

    public record IntakeResult(String action, Long alertId, Long incidentId, String fingerprint, String message) {
    }
}
