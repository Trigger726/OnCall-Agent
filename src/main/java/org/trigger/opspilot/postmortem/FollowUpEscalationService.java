package org.trigger.opspilot.postmortem;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.audit.AuditService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class FollowUpEscalationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcClient jdbcClient;
    private final AuditService auditService;

    public FollowUpEscalationService(JdbcClient jdbcClient, AuditService auditService) {
        this.jdbcClient = jdbcClient;
        this.auditService = auditService;
    }

    public LocalDate businessToday() {
        return LocalDate.now(BUSINESS_ZONE);
    }

    @Transactional
    public EscalationScanResult scan(LocalDate requestedAsOf, Long actorId, String sourceIp) {
        LocalDate asOf = requestedAsOf == null ? businessToday() : requestedAsOf;
        List<Long> candidates = jdbcClient.sql("""
                        SELECT id FROM postmortem_follow_up
                        WHERE status = 'OPEN' AND due_date < :asOf
                        ORDER BY due_date, id
                        """).param("asOf", asOf).query(Long.class).list();
        int created = 0;
        for (Long followUpId : candidates) {
            FollowUpContext context = lockContext(followUpId);
            if (!"OPEN".equals(context.status()) || !context.dueDate().isBefore(asOf)) continue;
            long existing = jdbcClient.sql("""
                            SELECT COUNT(*) FROM postmortem_follow_up_escalation
                            WHERE follow_up_id = :followUpId
                            """).param("followUpId", followUpId).query(Long.class).single();
            if (existing > 0) continue;
            jdbcClient.sql("""
                            INSERT INTO postmortem_follow_up_escalation(
                              follow_up_id, due_date_snapshot, detected_as_of, created_by)
                            VALUES (:followUpId, :dueDate, :asOf, :actorId)
                            """).param("followUpId", followUpId).param("dueDate", context.dueDate())
                    .param("asOf", asOf).param("actorId", actorId).update();
            addTimeline(context.incidentId(), "FOLLOW_UP_ESCALATED", actorId,
                    "防复发行动项逾期升级：" + context.title() + "；负责人 " + context.ownerName()
                            + "；截止 " + context.dueDate(), "postmortem-follow-up:" + followUpId);
            auditService.recordAs(actorId, sourceIp, "POSTMORTEM_FOLLOW_UP_ESCALATED",
                    "POSTMORTEM_FOLLOW_UP", followUpId,
                    "首次发现逾期；截止 " + context.dueDate() + "，扫描日期 " + asOf);
            created++;
        }
        return new EscalationScanResult(asOf, candidates.size(), created, candidates.size() - created);
    }

    @Transactional
    public boolean resolve(long followUpId, Long actorId, String sourceIp) {
        int updated = jdbcClient.sql("""
                        UPDATE postmortem_follow_up_escalation
                        SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP, resolved_by = :actorId
                        WHERE follow_up_id = :followUpId AND status = 'OPEN'
                        """).param("actorId", actorId).param("followUpId", followUpId).update();
        if (updated == 0) return false;
        FollowUpContext context = context(followUpId);
        addTimeline(context.incidentId(), "FOLLOW_UP_ESCALATION_RESOLVED", actorId,
                "逾期行动项完成，升级事实已关闭：" + context.title(),
                "postmortem-follow-up:" + followUpId);
        auditService.recordAs(actorId, sourceIp, "POSTMORTEM_FOLLOW_UP_ESCALATION_RESOLVED",
                "POSTMORTEM_FOLLOW_UP", followUpId, "行动项完成后关闭逾期事实");
        return true;
    }

    private FollowUpContext lockContext(long followUpId) {
        return findContext(followUpId, " FOR UPDATE");
    }

    private FollowUpContext context(long followUpId) {
        return findContext(followUpId, "");
    }

    private FollowUpContext findContext(long followUpId, String lockClause) {
        return jdbcClient.sql("""
                        SELECT follow_up.id, follow_up.title, follow_up.status, follow_up.due_date,
                               owner.display_name AS owner_name, postmortem.incident_id
                        FROM postmortem_follow_up follow_up
                        JOIN sys_user owner ON owner.id = follow_up.owner_id
                        JOIN incident_postmortem postmortem ON postmortem.id = follow_up.postmortem_id
                        WHERE follow_up.id = :id
                        """ + lockClause)
                .param("id", followUpId)
                .query((rs, rowNum) -> new FollowUpContext(
                        rs.getLong("id"), rs.getString("title"), rs.getString("status"),
                        rs.getObject("due_date", LocalDate.class), rs.getString("owner_name"),
                        rs.getLong("incident_id")))
                .single();
    }

    private void addTimeline(long incidentId, String eventType, Long actorId,
                             String content, String evidenceRef) {
        jdbcClient.sql("""
                        INSERT INTO incident_timeline(
                          incident_id, event_type, actor_id, content, evidence_ref, created_at)
                        VALUES (:incidentId, :eventType, :actorId, :content, :evidenceRef, :createdAt)
                        """).param("incidentId", incidentId).param("eventType", eventType)
                .param("actorId", actorId).param("content", content).param("evidenceRef", evidenceRef)
                .param("createdAt", LocalDateTime.now(BUSINESS_ZONE)).update();
    }

    public record EscalationScanResult(LocalDate asOf, int overdueItems,
                                       int createdEscalations, int existingEscalations) {
    }

    private record FollowUpContext(long id, String title, String status, LocalDate dueDate,
                                   String ownerName, long incidentId) {
    }
}
