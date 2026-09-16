package org.trigger.opspilot.investigation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.trigger.opspilot.audit.AuditService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AgentRunRecoveryService {
    private static final String TIMEOUT_REASON =
            "Agent 调查超过全链路超时预算且未形成终态，已由恢复协调器结算";
    private final JdbcClient jdbcClient;
    private final AgentRunEventService eventService;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;

    public AgentRunRecoveryService(JdbcClient jdbcClient,
                                   AgentRunEventService eventService,
                                   AuditService auditService,
                                   TransactionTemplate transactionTemplate,
                                   @Value("${opspilot.agent.recovery.batch-size:100}") int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Agent recovery batch size must be between 1 and 1000");
        }
        this.jdbcClient = jdbcClient;
        this.eventService = eventService;
        this.auditService = auditService;
        this.transactionTemplate = transactionTemplate;
        this.batchSize = batchSize;
    }

    public RecoveryResult reconcileExpired(LocalDateTime requestedAt) {
        LocalDateTime now = requestedAt.truncatedTo(ChronoUnit.MICROS);
        List<Long> candidates = jdbcClient.sql("""
                        SELECT id FROM agent_investigation_run
                        WHERE status IN ('QUEUED', 'RUNNING') AND deadline_at <= :now
                        ORDER BY deadline_at, id
                        LIMIT :limit
                        """)
                .param("now", now).param("limit", batchSize).query(Long.class).list();
        List<Long> settled = new ArrayList<>();
        for (long runId : candidates) {
            Boolean recovered = transactionTemplate.execute(status -> settle(runId, now));
            if (Boolean.TRUE.equals(recovered)) settled.add(runId);
        }
        return new RecoveryResult(candidates.size(), List.copyOf(settled));
    }

    private boolean settle(long runId, LocalDateTime now) {
        Candidate run = jdbcClient.sql("""
                        SELECT incident_id, status, started_at, deadline_at,
                               termination_kind, termination_reason
                        FROM agent_investigation_run
                        WHERE id = :runId
                        FOR UPDATE
                        """)
                .param("runId", runId)
                .query((rs, rowNum) -> new Candidate(
                        rs.getLong("incident_id"), rs.getString("status"),
                        rs.getObject("started_at", LocalDateTime.class),
                        rs.getObject("deadline_at", LocalDateTime.class),
                        rs.getString("termination_kind"), rs.getString("termination_reason")))
                .optional().orElse(null);
        if (run == null || !("QUEUED".equals(run.status()) || "RUNNING".equals(run.status()))
                || run.deadlineAt() == null || run.deadlineAt().isAfter(now)) {
            return false;
        }

        boolean cancelled = "CANCEL".equals(run.terminationKind());
        String terminalStatus = cancelled ? "CANCELLED" : "TIMED_OUT";
        String eventType = cancelled ? "RUN_CANCELLED" : "RUN_TIMED_OUT";
        String kind = cancelled ? "CANCEL" : "TIMEOUT";
        String reason = run.terminationReason() == null || run.terminationReason().isBlank()
                ? TIMEOUT_REASON : run.terminationReason();
        long durationMs = Math.max(0, Duration.between(run.startedAt(), now).toMillis());
        int updated = jdbcClient.sql("""
                        UPDATE agent_investigation_run
                        SET status = :terminalStatus, conclusion = :reason,
                            termination_kind = COALESCE(termination_kind, :kind),
                            termination_requested_at = COALESCE(termination_requested_at, :now),
                            termination_reason = COALESCE(termination_reason, :reason),
                            completed_at = :now, duration_ms = :durationMs
                        WHERE id = :runId AND status IN ('QUEUED', 'RUNNING')
                          AND deadline_at <= :now
                        """)
                .param("terminalStatus", terminalStatus).param("reason", reason)
                .param("kind", kind).param("now", now).param("durationMs", durationMs)
                .param("runId", runId).update();
        if (updated != 1) return false;

        eventService.record(runId, eventType, "FINISH", null, terminalStatus,
                Map.of("reason", reason, "recovered", true,
                        "recoveryMode", "DEADLINE_RECONCILIATION",
                        "previousStatus", run.status(), "deadlineAt", run.deadlineAt()),
                AgentRunEventService.EventSink.NOOP);
        String content = "Agent 调查 #" + runId + " 超过期限且未形成终态，恢复协调器结算为 " + terminalStatus;
        jdbcClient.sql("""
                        INSERT INTO incident_timeline(incident_id, event_type, content, evidence_ref)
                        VALUES (:incidentId, 'AGENT_RUN_RECOVERY', :content, :evidenceRef)
                        """)
                .param("incidentId", run.incidentId()).param("content", content)
                .param("evidenceRef", "agent-run:" + runId).update();
        auditService.recordAs(null, "scheduler", "AGENT_RUN_RECOVERY", "AGENT_RUN", runId, content);
        return true;
    }

    private record Candidate(long incidentId, String status, LocalDateTime startedAt,
                             LocalDateTime deadlineAt, String terminationKind,
                             String terminationReason) {
    }

    public record RecoveryResult(int scanned, List<Long> settledRunIds) {
        public int settled() {
            return settledRunIds.size();
        }
    }
}
