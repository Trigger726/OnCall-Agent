package org.trigger.opspilot.investigation;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.trigger.opspilot.common.ApiException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AgentRunQueryService {
    private final JdbcClient jdbcClient;

    public AgentRunQueryService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<AgentRunView> listByIncident(long incidentId) {
        return jdbcClient.sql("""
                        SELECT run.id, run.incident_id, run.status, run.trigger_source, run.plan_summary,
                               run.conclusion, run.report_id, creator.display_name AS created_by,
                               run.idempotency_key, run.deadline_at, run.termination_kind,
                               run.termination_requested_at, terminator.display_name AS termination_requested_by,
                               run.termination_reason, run.started_at, run.completed_at, run.duration_ms
                        FROM agent_investigation_run run
                        LEFT JOIN sys_user creator ON creator.id = run.created_by
                        LEFT JOIN sys_user terminator ON terminator.id = run.termination_requested_by
                        WHERE run.incident_id = :incidentId
                        ORDER BY run.created_at DESC, run.id DESC
                        """)
                .param("incidentId", incidentId)
                .query((rs, rowNum) -> mapRun(rs)).list().stream()
                .map(run -> run.withSteps(loadSteps(run.id())))
                .toList();
    }

    public AgentRunView get(long runId) {
        AgentRunView run = jdbcClient.sql("""
                        SELECT run.id, run.incident_id, run.status, run.trigger_source, run.plan_summary,
                               run.conclusion, run.report_id, creator.display_name AS created_by,
                               run.idempotency_key, run.deadline_at, run.termination_kind,
                               run.termination_requested_at, terminator.display_name AS termination_requested_by,
                               run.termination_reason, run.started_at, run.completed_at, run.duration_ms
                        FROM agent_investigation_run run
                        LEFT JOIN sys_user creator ON creator.id = run.created_by
                        LEFT JOIN sys_user terminator ON terminator.id = run.termination_requested_by
                        WHERE run.id = :runId
                        """)
                .param("runId", runId).query((rs, rowNum) -> mapRun(rs)).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "AGENT_RUN_NOT_FOUND", "Agent 调查运行不存在"));
        return run.withSteps(loadSteps(run.id()));
    }

    private List<AgentStepView> loadSteps(long runId) {
        return jdbcClient.sql("""
                        SELECT id, sequence_no, phase, tool_name, status, title, input_json,
                               output_summary, evidence_json, error_message, duration_ms,
                               started_at, completed_at
                        FROM agent_investigation_step WHERE run_id = :runId ORDER BY sequence_no
                        """)
                .param("runId", runId).query((rs, rowNum) -> new AgentStepView(
                        rs.getLong("id"), rs.getInt("sequence_no"), rs.getString("phase"),
                        rs.getString("tool_name"), rs.getString("status"), rs.getString("title"),
                        rs.getString("input_json"), rs.getString("output_summary"),
                        rs.getString("evidence_json"), rs.getString("error_message"),
                        rs.getLong("duration_ms"), rs.getObject("started_at", LocalDateTime.class),
                        rs.getObject("completed_at", LocalDateTime.class))).list();
    }

    private static AgentRunView mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AgentRunView(
                rs.getLong("id"), rs.getLong("incident_id"), rs.getString("status"),
                rs.getString("trigger_source"), rs.getString("plan_summary"), rs.getString("conclusion"),
                rs.getObject("report_id", Long.class), rs.getString("created_by"),
                rs.getString("idempotency_key"), rs.getObject("deadline_at", LocalDateTime.class),
                rs.getString("termination_kind"),
                rs.getObject("termination_requested_at", LocalDateTime.class),
                rs.getString("termination_requested_by"), rs.getString("termination_reason"),
                rs.getObject("started_at", LocalDateTime.class),
                rs.getObject("completed_at", LocalDateTime.class),
                rs.getObject("duration_ms", Long.class), List.of());
    }

    public record AgentRunView(Long id, Long incidentId, String status, String triggerSource,
                               String planSummary, String conclusion, Long reportId, String createdBy,
                               String idempotencyKey, LocalDateTime deadlineAt, String terminationKind,
                               LocalDateTime terminationRequestedAt, String terminationRequestedBy,
                               String terminationReason,
                               LocalDateTime startedAt, LocalDateTime completedAt, Long durationMs,
                               List<AgentStepView> steps) {
        AgentRunView withSteps(List<AgentStepView> value) {
            return new AgentRunView(id, incidentId, status, triggerSource, planSummary, conclusion,
                    reportId, createdBy, idempotencyKey, deadlineAt, terminationKind,
                    terminationRequestedAt, terminationRequestedBy, terminationReason,
                    startedAt, completedAt, durationMs, value);
        }
    }

    public record AgentStepView(Long id, int sequence, String phase, String toolName, String status,
                                String title, String inputJson, String outputSummary, String evidenceJson,
                                String errorMessage, long durationMs,
                                LocalDateTime startedAt, LocalDateTime completedAt) {
    }
}
