package org.trigger.opspilot.postmortem;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.common.PageResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

@Service
public class FollowUpOperationsService {
    private static final Set<String> STATUSES = Set.of("OPEN", "DONE");
    private static final Set<String> SCOPES = Set.of("ALL", "MINE");

    private final JdbcClient jdbcClient;
    private final FollowUpEscalationService escalationService;

    public FollowUpOperationsService(JdbcClient jdbcClient,
                                     FollowUpEscalationService escalationService) {
        this.jdbcClient = jdbcClient;
        this.escalationService = escalationService;
    }

    public PageResponse<FollowUpOperationsView> list(long userId, String scope, String status,
                                                     boolean overdue, LocalDate requestedAsOf,
                                                     int page, int size) {
        String normalizedScope = scope == null ? "ALL" : scope.trim().toUpperCase();
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        if (!SCOPES.contains(normalizedScope)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FOLLOW_UP_INVALID_SCOPE",
                    "行动项范围仅支持 ALL 或 MINE");
        }
        if (!normalizedStatus.isBlank() && !STATUSES.contains(normalizedStatus)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FOLLOW_UP_INVALID_STATUS",
                    "行动项状态仅支持 OPEN 或 DONE");
        }
        LocalDate asOf = requestedAsOf == null ? escalationService.businessToday() : requestedAsOf;
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String where = """
                WHERE (:mine = 0 OR follow_up.owner_id = :userId)
                  AND (:status = '' OR follow_up.status = :status)
                  AND (:overdue = 0 OR (follow_up.status = 'OPEN' AND follow_up.due_date < :asOf))
                """;
        long total = jdbcClient.sql("SELECT COUNT(*) FROM postmortem_follow_up follow_up " + where)
                .param("mine", "MINE".equals(normalizedScope) ? 1 : 0).param("userId", userId)
                .param("status", normalizedStatus).param("overdue", overdue ? 1 : 0)
                .param("asOf", asOf).query(Long.class).single();
        List<FollowUpOperationsView> items = jdbcClient.sql("""
                        SELECT follow_up.id, follow_up.postmortem_id, postmortem.incident_id,
                               incident.incident_code, incident.title AS incident_title,
                               incident.severity, follow_up.title, follow_up.description,
                               follow_up.priority, follow_up.status, follow_up.owner_id,
                               owner.display_name AS owner_name, follow_up.due_date,
                               follow_up.completed_at, follow_up.version,
                               escalation.status AS escalation_status,
                               escalation.detected_as_of, escalation.first_detected_at,
                               escalation.resolved_at
                        FROM postmortem_follow_up follow_up
                        JOIN incident_postmortem postmortem ON postmortem.id = follow_up.postmortem_id
                        JOIN incident ON incident.id = postmortem.incident_id
                        JOIN sys_user owner ON owner.id = follow_up.owner_id
                        LEFT JOIN postmortem_follow_up_escalation escalation
                          ON escalation.follow_up_id = follow_up.id
                        """ + where + """
                        ORDER BY CASE
                                   WHEN follow_up.status = 'OPEN' AND follow_up.due_date < :asOf THEN 1
                                   WHEN follow_up.status = 'OPEN' THEN 2 ELSE 3 END,
                                 follow_up.due_date, follow_up.id
                        LIMIT :limit OFFSET :offset
                        """)
                .param("mine", "MINE".equals(normalizedScope) ? 1 : 0).param("userId", userId)
                .param("status", normalizedStatus).param("overdue", overdue ? 1 : 0)
                .param("asOf", asOf).param("limit", safeSize)
                .param("offset", (safePage - 1) * safeSize)
                .query((rs, rowNum) -> {
                    LocalDate dueDate = rs.getObject("due_date", LocalDate.class);
                    String itemStatus = rs.getString("status");
                    boolean isOverdue = "OPEN".equals(itemStatus) && dueDate.isBefore(asOf);
                    return new FollowUpOperationsView(
                            rs.getLong("id"), rs.getLong("postmortem_id"),
                            rs.getLong("incident_id"), rs.getString("incident_code"),
                            rs.getString("incident_title"), rs.getString("severity"),
                            rs.getString("title"), rs.getString("description"),
                            rs.getString("priority"), itemStatus, rs.getLong("owner_id"),
                            rs.getString("owner_name"), dueDate, isOverdue,
                            isOverdue ? ChronoUnit.DAYS.between(dueDate, asOf) : 0,
                            rs.getString("escalation_status"),
                            rs.getObject("detected_as_of", LocalDate.class),
                            rs.getObject("first_detected_at", LocalDateTime.class),
                            rs.getObject("resolved_at", LocalDateTime.class),
                            rs.getObject("completed_at", LocalDateTime.class), rs.getInt("version"));
                }).list();
        return new PageResponse<>(items, total, safePage, safeSize);
    }

    public record FollowUpOperationsView(
            long id, long postmortemId, long incidentId, String incidentCode,
            String incidentTitle, String severity, String title, String description,
            String priority, String status, long ownerId, String ownerName,
            LocalDate dueDate, boolean overdue, long daysOverdue,
            String escalationStatus, LocalDate detectedAsOf,
            LocalDateTime firstDetectedAt, LocalDateTime escalationResolvedAt,
            LocalDateTime completedAt, int version) {
    }
}
