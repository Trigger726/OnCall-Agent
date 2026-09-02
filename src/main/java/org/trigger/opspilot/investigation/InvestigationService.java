package org.trigger.opspilot.investigation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.incident.IncidentService;
import org.trigger.opspilot.investigation.tool.InvestigationTool;
import org.trigger.opspilot.investigation.tool.InvestigationTool.ToolEvidence;
import org.trigger.opspilot.remediation.RemediationProposalService;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class InvestigationService {
    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);
    private static final String PLAN_SUMMARY =
            "按告警、CMDB、指标、变更、日志、Runbook 六个只读工具依次取证，再依据证据完整度重新规划并输出结论。";

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final IncidentService incidentService;
    private final AuditService auditService;
    private final ObjectProvider<AiNarrativeService> aiNarrativeService;
    private final AgentRunQueryService agentRunQueryService;
    private final AgentRunEventService agentRunEventService;
    private final RemediationProposalService remediationProposalService;
    private final TransactionTemplate transactionTemplate;
    private final List<InvestigationTool> tools;
    private final Duration defaultExecutionTimeout;
    private final Duration maxExecutionTimeout;

    public InvestigationService(JdbcClient jdbcClient, ObjectMapper objectMapper,
                                IncidentService incidentService, AuditService auditService,
                                ObjectProvider<AiNarrativeService> aiNarrativeService,
                                AgentRunQueryService agentRunQueryService,
                                AgentRunEventService agentRunEventService,
                                RemediationProposalService remediationProposalService,
                                TransactionTemplate transactionTemplate,
                                List<InvestigationTool> tools,
                                @Value("${opspilot.agent.execution-timeout:60s}") Duration defaultExecutionTimeout,
                                @Value("${opspilot.agent.max-execution-timeout:5m}") Duration maxExecutionTimeout) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.incidentService = incidentService;
        this.auditService = auditService;
        this.aiNarrativeService = aiNarrativeService;
        this.agentRunQueryService = agentRunQueryService;
        this.agentRunEventService = agentRunEventService;
        this.remediationProposalService = remediationProposalService;
        this.transactionTemplate = transactionTemplate;
        this.tools = tools.stream().sorted(Comparator.comparingInt(InvestigationTool::order)).toList();
        this.defaultExecutionTimeout = defaultExecutionTimeout;
        this.maxExecutionTimeout = maxExecutionTimeout;
    }

    public InvestigationResult investigate(long incidentId, String rawTriggerSource) {
        RunActor actor = new RunActor(auditService.currentUserId(), auditService.currentIp());
        PreparedRun prepared = prepare(incidentId, rawTriggerSource, actor, null, null);
        return execute(prepared, actor, AgentRunEventService.EventSink.NOOP);
    }

    public InvestigationResult investigate(long incidentId, String rawTriggerSource,
                                           RunActor actor, AgentRunEventService.EventSink eventSink) {
        PreparedRun prepared = prepare(incidentId, rawTriggerSource, actor, null, null);
        return execute(prepared, actor, eventSink);
    }

    public PreparedRun prepare(long incidentId, String rawTriggerSource, RunActor actor,
                               String rawIdempotencyKey, Duration requestedTimeout) {
        incidentService.findSummary(incidentId);
        String triggerSource = normalizeTriggerSource(rawTriggerSource);
        String idempotencyKey = normalizeIdempotencyKey(rawIdempotencyKey);
        Duration timeout = normalizeTimeout(requestedTimeout);
        LocalDateTime queuedAt = LocalDateTime.now();
        LocalDateTime deadlineAt = queuedAt.plus(timeout);
        try {
            long runId = createRun(incidentId, triggerSource, actor.userId(), idempotencyKey,
                    queuedAt, deadlineAt);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("incidentId", incidentId);
            payload.put("triggerSource", triggerSource);
            payload.put("deadlineAt", deadlineAt);
            payload.put("timeoutMs", timeout.toMillis());
            if (idempotencyKey != null) payload.put("idempotencyKey", idempotencyKey);
            agentRunEventService.record(runId, "RUN_QUEUED", null, null, "QUEUED",
                    payload, AgentRunEventService.EventSink.NOOP);
            return new PreparedRun(runId, incidentId, triggerSource, idempotencyKey,
                    deadlineAt, false, "QUEUED");
        } catch (DuplicateKeyException exception) {
            return findPreparedRun(incidentId, idempotencyKey, true);
        }
    }

    public InvestigationResult execute(PreparedRun prepared, RunActor actor,
                                       AgentRunEventService.EventSink eventSink) {
        long incidentId = prepared.incidentId();
        String triggerSource = prepared.triggerSource();
        IncidentService.IncidentDetail detail = incidentService.get(incidentId);
        LocalDateTime runStartedAt = LocalDateTime.now();
        long runId = prepared.runId();
        if (!startRun(runId, runStartedAt)) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_RUN_NOT_QUEUED",
                    "Agent 调查运行当前不可启动，运行 #" + runId);
        }
        int sequence = 1;
        List<ToolEvidence> evidence = new ArrayList<>();
        int failedTools = 0;

        recordEvent(runId, "RUN_STARTED", null, null, "RUNNING",
                Map.of("incidentId", incidentId, "triggerSource", triggerSource,
                        "planSummary", PLAN_SUMMARY, "deadlineAt", prepared.deadlineAt()), eventSink);

        int planStepSequence = sequence++;
        insertStep(runId, planStepSequence, "PLAN", null, "SUCCEEDED", "制定调查计划",
                serialize(Map.of("incidentId", incidentId, "resourceId", detail.incident().resourceId())),
                PLAN_SUMMARY, "[]", null, 0, runStartedAt, runStartedAt);
        recordEvent(runId, "PLAN_COMPLETED", "PLAN", null, "SUCCEEDED",
                Map.of("stepSequence", planStepSequence, "title", "制定调查计划",
                        "summary", PLAN_SUMMARY), eventSink);

        try {
            for (InvestigationTool tool : tools) {
                checkControl(runId);
                int stepSequence = sequence++;
                LocalDateTime startedAt = LocalDateTime.now();
                String inputJson = serialize(Map.of(
                        "incidentId", incidentId,
                        "incidentCode", detail.incident().incidentCode(),
                        "resourceId", detail.incident().resourceId(),
                        "readOnly", true));
                recordEvent(runId, "STEP_STARTED", "EXECUTE", tool.name(), "RUNNING",
                        Map.of("stepSequence", stepSequence, "title", tool.title()), eventSink);
                try {
                    InvestigationTool.ToolResult result = tool.execute(
                            detail, new InvestigationTool.ToolContext(actor.userId()));
                    evidence.addAll(result.evidence());
                    LocalDateTime completedAt = LocalDateTime.now();
                    Map<String, Object> tracedInput = new LinkedHashMap<>();
                    tracedInput.put("incidentId", incidentId);
                    tracedInput.put("incidentCode", detail.incident().incidentCode());
                    tracedInput.put("resourceId", detail.incident().resourceId());
                    tracedInput.put("readOnly", true);
                    tracedInput.putAll(result.traceMetadata());
                    String stepStatus = result.evidence().isEmpty() ? "NO_DATA" : "SUCCEEDED";
                    long durationMs = elapsedMillis(startedAt, completedAt);
                    insertStep(runId, stepSequence, "EXECUTE", tool.name(),
                            stepStatus, tool.title(), serialize(tracedInput),
                            result.summary(), serialize(result.evidence()), null,
                            durationMs, startedAt, completedAt);
                    recordEvent(runId,
                            result.evidence().isEmpty() ? "STEP_COMPLETED" : "EVIDENCE_COLLECTED",
                            "EXECUTE", tool.name(), stepStatus,
                            Map.of("stepSequence", stepSequence, "title", tool.title(),
                                    "summary", result.summary(), "evidenceCount", result.evidence().size(),
                                    "evidenceRefs", result.evidence().stream().map(ToolEvidence::ref).toList(),
                                    "durationMs", durationMs, "metadata", result.traceMetadata()), eventSink);
                    checkControl(runId);
                } catch (RuntimeException exception) {
                    if (exception instanceof RunTerminatedException) throw exception;
                    TerminationSignal termination = detectTermination(runId);
                    if (termination != null) {
                        LocalDateTime completedAt = LocalDateTime.now();
                        long durationMs = elapsedMillis(startedAt, completedAt);
                        insertStep(runId, stepSequence, "EXECUTE", tool.name(), termination.status(), tool.title(),
                                inputJson, termination.reason(), "[]", termination.reason(),
                                durationMs, startedAt, completedAt);
                        recordEvent(runId, "CANCELLED".equals(termination.status())
                                        ? "STEP_CANCELLED" : "STEP_TIMED_OUT",
                                "EXECUTE", tool.name(), termination.status(),
                                Map.of("stepSequence", stepSequence, "title", tool.title(),
                                        "reason", termination.reason(), "durationMs", durationMs), eventSink);
                        throw new RunTerminatedException(termination);
                    }
                    failedTools++;
                    LocalDateTime completedAt = LocalDateTime.now();
                    String error = clippedError(exception);
                    long durationMs = elapsedMillis(startedAt, completedAt);
                    insertStep(runId, stepSequence, "EXECUTE", tool.name(), "FAILED", tool.title(), inputJson,
                            "工具执行失败，已保留其他证据源并继续调查。", "[]", error,
                            durationMs, startedAt, completedAt);
                    recordEvent(runId, "STEP_FAILED", "EXECUTE", tool.name(), "FAILED",
                            Map.of("stepSequence", stepSequence, "title", tool.title(),
                                    "error", error, "durationMs", durationMs), eventSink);
                    log.warn("Investigation tool {} failed for run {}: {}", tool.name(), runId, error);
                }
            }

            checkControl(runId);
            RuleConclusion conclusion = conclude(detail, evidence);
            String replanSummary = buildReplanSummary(evidence, failedTools);
            LocalDateTime replanAt = LocalDateTime.now();
            int replanStepSequence = sequence++;
            insertStep(runId, replanStepSequence, "REPLAN", null, "SUCCEEDED", "评估证据并重新规划",
                    serialize(Map.of("evidenceCount", evidence.size(), "failedToolCount", failedTools)),
                    replanSummary, serialize(evidence), null, 0, replanAt, replanAt);
            recordEvent(runId, "REPLAN_COMPLETED", "REPLAN", null, "SUCCEEDED",
                    Map.of("stepSequence", replanStepSequence, "summary", replanSummary,
                            "evidenceCount", evidence.size(), "failedToolCount", failedTools), eventSink);

            String engine = "AGENT_TOOLCHAIN";
            String summary = conclusion.summary();
            checkControl(runId);
            AiNarrativeService ai = aiNarrativeService.getIfAvailable();
            if (ai != null) {
                try {
                    summary = ai.narrate(detail.incident().title(), conclusion.hypothesis(), serialize(evidence));
                    engine = "DASHSCOPE_AGENT";
                } catch (RuntimeException exception) {
                    log.warn("AI narrative failed for run {}, using deterministic conclusion: {}", runId,
                            exception.getMessage());
                }
            }
            checkControl(runId);

            String runStatus = failedTools == 0 ? "COMPLETED" : "PARTIAL";
            String finalEngine = engine;
            String finalSummary = summary;
            int finishSequence = sequence;
            FinalizedReport finalized = transactionTemplate.execute(transaction -> {
                long reportId = createReport(incidentId, finalEngine, runStatus, finalSummary,
                        conclusion, evidence, actor.userId());
                LocalDateTime finishedAt = LocalDateTime.now();
                insertStep(runId, finishSequence, "FINISH", null, "SUCCEEDED", "生成调查结论",
                        serialize(Map.of("reportId", reportId, "runStatus", runStatus)),
                        conclusion.hypothesis(), serialize(List.of(
                                new ToolEvidence("REPORT", "investigation:" + reportId, finishedAt, finalSummary))),
                        null, 0, finishedAt, finishedAt);
                finishRun(runId, runStatus, conclusion.hypothesis(), reportId, runStartedAt, finishedAt);
                addTimelineAndAudit(incidentId, runId, reportId, runStatus, conclusion.confidence(),
                        triggerSource, actor);
                RemediationProposalService.ProposalView proposal = remediationProposalService.createForRun(
                        incidentId, runId, actor.userId(), actor.ipAddress(),
                        conclusion.confidence(), evidence);
                return new FinalizedReport(reportId, proposal == null ? null : proposal.id(), finishedAt);
            });
            if (finalized == null) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "AGENT_RUN_FINALIZE_FAILED", "Agent 调查无法完成最终提交");
            }

            if (finalized.proposalId() != null) {
                recordEvent(runId, "ACTION_PROPOSED", "FINISH", null, "PENDING_APPROVAL",
                        Map.of("proposalId", finalized.proposalId(), "riskLevel", "HIGH",
                                "actionType", "ROLLBACK_CHANGE"), eventSink);
            }
            recordEvent(runId, "RUN_COMPLETED", "FINISH", null, runStatus,
                    Map.of("reportId", finalized.reportId(), "status", runStatus,
                            "confidence", conclusion.confidence(), "evidenceCount", evidence.size(),
                            "proposalId", finalized.proposalId() == null ? 0 : finalized.proposalId()), eventSink);

            AgentRunQueryService.AgentRunView run = agentRunQueryService.get(runId);
            return new InvestigationResult(runId, finalized.reportId(), runStatus, finalEngine, finalSummary,
                    conclusion.hypothesis(), conclusion.confidence(), conclusion.suggestions(),
                    List.copyOf(evidence), run.steps());
        } catch (RunTerminatedException exception) {
            TerminationSignal termination = exception.termination();
            LocalDateTime completedAt = LocalDateTime.now();
            terminateRun(runId, termination, runStartedAt, completedAt);
            recordEvent(runId, "CANCELLED".equals(termination.status())
                            ? "RUN_CANCELLED" : "RUN_TIMED_OUT",
                    "FINISH", null, termination.status(),
                    Map.of("reason", termination.reason()), eventSink);
            AgentRunQueryService.AgentRunView run = agentRunQueryService.get(runId);
            return new InvestigationResult(runId, null, termination.status(), "AGENT_TOOLCHAIN",
                    termination.reason(), termination.reason(), null, null,
                    List.copyOf(evidence), run.steps());
        } catch (RuntimeException exception) {
            LocalDateTime failedAt = LocalDateTime.now();
            failRun(runId, clippedError(exception), runStartedAt, failedAt);
            try {
                recordEvent(runId, "RUN_FAILED", "FINISH", null, "FAILED",
                        Map.of("error", clippedError(exception)), eventSink);
            } catch (RuntimeException eventException) {
                log.warn("Unable to persist final failure event for run {}: {}", runId, eventException.getMessage());
            }
            throw exception;
        }
    }

    public List<AgentRunQueryService.AgentRunView> listRuns(long incidentId) {
        incidentService.findSummary(incidentId);
        return agentRunQueryService.listByIncident(incidentId);
    }

    public AgentRunQueryService.AgentRunView requestCancellation(long runId, RunActor actor, String rawReason) {
        String reason = normalizeTerminationReason(rawReason, "用户请求取消 Agent 调查");
        TerminationRequest request = requestTermination(runId, "CANCEL", "CANCELLED",
                actor.userId(), reason, AgentRunEventService.EventSink.NOOP);
        if (request.changed()) {
            jdbcClient.sql("""
                            INSERT INTO incident_timeline(incident_id, event_type, actor_id, content, evidence_ref)
                            VALUES (:incidentId, 'AGENT_RUN_CANCEL', :actorId, :content, :evidenceRef)
                            """)
                    .param("incidentId", request.incidentId()).param("actorId", actor.userId())
                    .param("content", "请求取消 Agent 调查运行 #" + runId + "：" + reason)
                    .param("evidenceRef", "agent-run:" + runId).update();
            auditService.recordAs(actor.userId(), actor.ipAddress(),
                    "AGENT_RUN_CANCEL", "AGENT_RUN", runId, reason);
        }
        return agentRunQueryService.get(runId);
    }

    public void requestTimeout(long runId) {
        requestTermination(runId, "TIMEOUT", "TIMED_OUT", null,
                "Agent 调查超过全链路超时预算", AgentRunEventService.EventSink.NOOP);
    }

    public void rejectQueue(long runId, RuntimeException exception,
                            AgentRunEventService.EventSink eventSink) {
        LocalDateTime rejectedAt = LocalDateTime.now();
        String reason = "Agent 执行队列已饱和，请稍后重试";
        int updated = jdbcClient.sql("""
                        UPDATE agent_investigation_run
                        SET status = 'QUEUE_REJECTED', conclusion = :reason,
                            termination_kind = 'QUEUE', termination_requested_at = :rejectedAt,
                            termination_reason = :reason, completed_at = :rejectedAt,
                            duration_ms = 0
                        WHERE id = :runId AND status = 'QUEUED'
                        """)
                .param("reason", reason).param("rejectedAt", rejectedAt).param("runId", runId).update();
        if (updated > 0) {
            recordEvent(runId, "RUN_REJECTED", null, null, "QUEUE_REJECTED",
                    Map.of("reason", reason, "retryable", true), eventSink);
            log.warn("Agent run {} rejected by bounded executor: {}", runId, clippedError(exception));
        }
    }

    private long createRun(long incidentId, String triggerSource, Long creatorId,
                           String idempotencyKey, LocalDateTime queuedAt, LocalDateTime deadlineAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO agent_investigation_run(
                          incident_id, status, trigger_source, plan_summary, created_by,
                          idempotency_key, deadline_at, started_at)
                        VALUES (:incidentId, 'QUEUED', :triggerSource, :planSummary, :createdBy,
                          :idempotencyKey, :deadlineAt, :queuedAt)
                        """)
                .param("incidentId", incidentId).param("triggerSource", triggerSource)
                .param("planSummary", PLAN_SUMMARY).param("createdBy", creatorId)
                .param("idempotencyKey", idempotencyKey).param("deadlineAt", deadlineAt)
                .param("queuedAt", queuedAt).update(keyHolder, "id");
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "AGENT_RUN_CREATE_FAILED", "无法创建 Agent 调查运行");
        }
        return key.longValue();
    }

    private PreparedRun findPreparedRun(long incidentId, String idempotencyKey, boolean reused) {
        return jdbcClient.sql("""
                        SELECT id, incident_id, trigger_source, idempotency_key, deadline_at, status
                        FROM agent_investigation_run
                        WHERE incident_id = :incidentId AND idempotency_key = :idempotencyKey
                        """)
                .param("incidentId", incidentId).param("idempotencyKey", idempotencyKey)
                .query((rs, rowNum) -> new PreparedRun(
                        rs.getLong("id"), rs.getLong("incident_id"), rs.getString("trigger_source"),
                        rs.getString("idempotency_key"),
                        rs.getObject("deadline_at", LocalDateTime.class), reused, rs.getString("status")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "AGENT_IDEMPOTENCY_CONFLICT", "幂等键冲突，但未能读取已有运行"));
    }

    private boolean startRun(long runId, LocalDateTime startedAt) {
        return jdbcClient.sql("""
                        UPDATE agent_investigation_run
                        SET status = 'RUNNING', started_at = :startedAt
                        WHERE id = :runId AND status = 'QUEUED'
                        """)
                .param("startedAt", startedAt).param("runId", runId).update() == 1;
    }

    private long createReport(long incidentId, String engine, String status, String summary,
                              RuleConclusion conclusion, List<ToolEvidence> evidence, Long creatorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO investigation_report(incident_id, engine, status, summary, hypothesis,
                          confidence, suggestions, evidence_json, created_by)
                        VALUES (:incidentId, :engine, :status, :summary, :hypothesis,
                          :confidence, :suggestions, :evidence, :createdBy)
                        """)
                .param("incidentId", incidentId).param("engine", engine).param("status", status)
                .param("summary", summary).param("hypothesis", conclusion.hypothesis())
                .param("confidence", conclusion.confidence()).param("suggestions", conclusion.suggestions())
                .param("evidence", serialize(evidence)).param("createdBy", creatorId)
                .update(keyHolder, "id");
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "INVESTIGATION_REPORT_CREATE_FAILED", "无法保存调查报告");
        }
        return key.longValue();
    }

    private void insertStep(long runId, int sequence, String phase, String toolName, String status,
                            String title, String inputJson, String outputSummary, String evidenceJson,
                            String errorMessage, long durationMs,
                            LocalDateTime startedAt, LocalDateTime completedAt) {
        jdbcClient.sql("""
                        INSERT INTO agent_investigation_step(
                          run_id, sequence_no, phase, tool_name, status, title, input_json,
                          output_summary, evidence_json, error_message, duration_ms, started_at, completed_at)
                        VALUES (:runId, :sequence, :phase, :toolName, :status, :title, :inputJson,
                          :outputSummary, :evidenceJson, :errorMessage, :durationMs, :startedAt, :completedAt)
                        """)
                .param("runId", runId).param("sequence", sequence).param("phase", phase)
                .param("toolName", toolName).param("status", status).param("title", title)
                .param("inputJson", inputJson).param("outputSummary", outputSummary)
                .param("evidenceJson", evidenceJson).param("errorMessage", errorMessage)
                .param("durationMs", durationMs).param("startedAt", startedAt)
                .param("completedAt", completedAt).update();
    }

    private void finishRun(long runId, String status, String conclusion, long reportId,
                           LocalDateTime startedAt, LocalDateTime completedAt) {
        int updated = jdbcClient.sql("""
                        UPDATE agent_investigation_run
                        SET status = :status, conclusion = :conclusion, report_id = :reportId,
                            completed_at = :completedAt, duration_ms = :durationMs
                        WHERE id = :runId AND status = 'RUNNING'
                          AND termination_kind IS NULL AND deadline_at > :completedAt
                        """)
                .param("status", status).param("conclusion", conclusion).param("reportId", reportId)
                .param("completedAt", completedAt).param("durationMs", elapsedMillis(startedAt, completedAt))
                .param("runId", runId).update();
        if (updated == 0) {
            TerminationSignal termination = signalFrom(loadControlState(runId));
            if (termination == null) {
                termination = new TerminationSignal("TIMED_OUT", "Agent 调查超过全链路超时预算");
            }
            throw new RunTerminatedException(termination);
        }
    }

    private void failRun(long runId, String error, LocalDateTime startedAt, LocalDateTime failedAt) {
        jdbcClient.sql("""
                        UPDATE agent_investigation_run
                        SET status = 'FAILED', conclusion = :error, completed_at = :failedAt, duration_ms = :durationMs
                        WHERE id = :runId AND status = 'RUNNING'
                        """)
                .param("error", error).param("failedAt", failedAt)
                .param("durationMs", elapsedMillis(startedAt, failedAt)).param("runId", runId).update();
    }

    private void terminateRun(long runId, TerminationSignal termination,
                              LocalDateTime startedAt, LocalDateTime completedAt) {
        String kind = "CANCELLED".equals(termination.status()) ? "CANCEL" : "TIMEOUT";
        jdbcClient.sql("""
                        UPDATE agent_investigation_run
                        SET status = :status, conclusion = :reason, completed_at = :completedAt,
                            duration_ms = :durationMs,
                            termination_kind = COALESCE(termination_kind, :kind),
                            termination_requested_at = COALESCE(termination_requested_at, :completedAt),
                            termination_reason = COALESCE(termination_reason, :reason)
                        WHERE id = :runId AND status = 'RUNNING'
                        """)
                .param("status", termination.status()).param("reason", termination.reason())
                .param("completedAt", completedAt)
                .param("durationMs", elapsedMillis(startedAt, completedAt))
                .param("kind", kind).param("runId", runId).update();
    }

    private void addTimelineAndAudit(long incidentId, long runId, long reportId, String status,
                                     BigDecimal confidence, String triggerSource, RunActor actor) {
        jdbcClient.sql("""
                        INSERT INTO incident_timeline(incident_id, event_type, actor_id, content, evidence_ref)
                        VALUES (:incidentId, 'AGENT_INVESTIGATION', :actorId, :content, :evidenceRef)
                        """)
                .param("incidentId", incidentId).param("actorId", actor.userId())
                .param("content", "Agent 调查 " + status + "，运行 #" + runId + "，置信度 " + confidence)
                .param("evidenceRef", "agent-run:" + runId).update();
        auditService.recordAs(actor.userId(), actor.ipAddress(),
                "AGENT_INVESTIGATION", "INCIDENT", incidentId,
                "运行 #" + runId + " 生成报告 #" + reportId + "，入口 " + triggerSource + "，状态 " + status);
    }

    private void recordEvent(long runId, String eventType,
                             String phase, String toolName, String status,
                             Map<String, Object> payload, AgentRunEventService.EventSink sink) {
        agentRunEventService.record(runId, eventType, phase, toolName, status, payload, sink);
    }

    private TerminationRequest requestTermination(long runId, String kind, String terminalStatus,
                                                  Long requesterId, String reason,
                                                  AgentRunEventService.EventSink eventSink) {
        RunControlState state = loadControlState(runId);
        if (isTerminal(state.status())) {
            return new TerminationRequest(state.incidentId(), false);
        }
        LocalDateTime requestedAt = LocalDateTime.now();
        int updated;
        String eventType;
        String eventStatus;
        if ("QUEUED".equals(state.status())) {
            updated = jdbcClient.sql("""
                            UPDATE agent_investigation_run
                            SET status = :terminalStatus, conclusion = :reason,
                                termination_kind = :kind, termination_requested_at = :requestedAt,
                                termination_requested_by = :requesterId, termination_reason = :reason,
                                completed_at = :requestedAt,
                                duration_ms = :durationMs
                            WHERE id = :runId AND status = 'QUEUED'
                            """)
                    .param("terminalStatus", terminalStatus).param("reason", reason).param("kind", kind)
                    .param("requestedAt", requestedAt).param("requesterId", requesterId)
                    .param("durationMs", elapsedMillis(state.startedAt(), requestedAt))
                    .param("runId", runId).update();
            eventType = "CANCEL".equals(kind) ? "RUN_CANCELLED" : "RUN_TIMED_OUT";
            eventStatus = terminalStatus;
        } else {
            updated = jdbcClient.sql("""
                            UPDATE agent_investigation_run
                            SET termination_kind = :kind, termination_requested_at = :requestedAt,
                                termination_requested_by = :requesterId, termination_reason = :reason
                            WHERE id = :runId AND status = 'RUNNING' AND termination_kind IS NULL
                            """)
                    .param("kind", kind).param("requestedAt", requestedAt)
                    .param("requesterId", requesterId).param("reason", reason)
                    .param("runId", runId).update();
            eventType = "CANCEL".equals(kind) ? "RUN_CANCEL_REQUESTED" : "RUN_TIMEOUT_REQUESTED";
            eventStatus = "RUNNING";
        }
        if (updated > 0) {
            recordEvent(runId, eventType, null, null, eventStatus,
                    Map.of("reason", reason), eventSink);
        }
        return new TerminationRequest(state.incidentId(), updated > 0);
    }

    private void checkControl(long runId) {
        TerminationSignal termination = detectTermination(runId);
        if (termination != null) throw new RunTerminatedException(termination);
    }

    private TerminationSignal detectTermination(long runId) {
        RunControlState state = loadControlState(runId);
        TerminationSignal signal = signalFrom(state);
        if (signal != null) return signal;
        if (state.deadlineAt() != null && !LocalDateTime.now().isBefore(state.deadlineAt())) {
            requestTimeout(runId);
            return new TerminationSignal("TIMED_OUT", "Agent 调查超过全链路超时预算");
        }
        if (Thread.currentThread().isInterrupted()) {
            requestTermination(runId, "CANCEL", "CANCELLED", null,
                    "Agent 调查执行线程已被中断", AgentRunEventService.EventSink.NOOP);
            return new TerminationSignal("CANCELLED", "Agent 调查执行线程已被中断");
        }
        return null;
    }

    private RunControlState loadControlState(long runId) {
        return jdbcClient.sql("""
                        SELECT incident_id, status, deadline_at, termination_kind, termination_reason, started_at
                        FROM agent_investigation_run WHERE id = :runId
                        """)
                .param("runId", runId)
                .query((rs, rowNum) -> new RunControlState(
                        rs.getLong("incident_id"), rs.getString("status"),
                        rs.getObject("deadline_at", LocalDateTime.class),
                        rs.getString("termination_kind"), rs.getString("termination_reason"),
                        rs.getObject("started_at", LocalDateTime.class)))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "AGENT_RUN_NOT_FOUND", "Agent 调查运行不存在"));
    }

    private static TerminationSignal signalFrom(RunControlState state) {
        if ("CANCELLED".equals(state.status()) || "CANCEL".equals(state.terminationKind())) {
            return new TerminationSignal("CANCELLED",
                    defaultIfBlank(state.terminationReason(), "Agent 调查已取消"));
        }
        if ("TIMED_OUT".equals(state.status()) || "TIMEOUT".equals(state.terminationKind())) {
            return new TerminationSignal("TIMED_OUT",
                    defaultIfBlank(state.terminationReason(), "Agent 调查超过全链路超时预算"));
        }
        return null;
    }

    private static boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "PARTIAL".equals(status) || "FAILED".equals(status)
                || "CANCELLED".equals(status) || "TIMED_OUT".equals(status)
                || "QUEUE_REJECTED".equals(status);
    }

    private RuleConclusion conclude(IncidentService.IncidentDetail detail, List<ToolEvidence> evidence) {
        List<ToolEvidence> changes = evidence.stream().filter(item -> "CHANGE".equals(item.type())).toList();
        String alertText = detail.alerts().stream().map(IncidentService.AlertView::title)
                .reduce("", (left, right) -> left + " " + right).toLowerCase(Locale.ROOT);
        if (!changes.isEmpty() && (alertText.contains("redis") || alertText.contains("连接池") || alertText.contains("超时"))) {
            return new RuleConclusion(
                    "工具链在故障窗口内发现依赖组件变更，并与连接等待及接口超时证据同时出现。",
                    "近期变更可能降低了缓存连接承载能力，导致请求排队并向上游放大为接口超时。该结论仍需通过回滚或指标对照验证。",
                    new BigDecimal("0.88"),
                    "1. 核对变更前后连接池 active/pending；2. 在审批后回滚相关配置；3. 连续观察接口 P95 与错误率 15 分钟；4. 补充容量基线和变更前置检查。"
            );
        }
        if (!changes.isEmpty()) {
            return new RuleConclusion(
                    "工具链发现 Incident 前后存在关联资源变更，时间相关性明确但因果证据不足。",
                    "最近变更是当前优先验证方向，尚不能直接认定为根因。",
                    new BigDecimal("0.68"),
                    "对照变更批次、错误码和核心指标；进行小范围回滚或流量隔离验证；保留验证结果到时间线。"
            );
        }
        return new RuleConclusion(
                "工具链已查询告警、依赖、变更与 Runbook，但未发现故障窗口内的直接变更证据。",
                "当前证据不足以确定根因，应继续采集应用日志、依赖延迟和资源饱和度。",
                new BigDecimal("0.42"),
                "按依赖拓扑逐层查询 P95、错误率和饱和度；补充关键日志证据；在证据完整前避免执行高风险操作。"
        );
    }

    private static String buildReplanSummary(List<ToolEvidence> evidence, int failedTools) {
        long evidenceTypes = evidence.stream().map(ToolEvidence::type).distinct().count();
        String failure = failedTools == 0 ? "所有工具均完成。" : failedTools + " 个工具失败，结论已降级并保留失败原因。";
        return "汇总 " + evidence.size() + " 条证据，覆盖 " + evidenceTypes + " 类数据源。" + failure
                + "下一步仅给出可验证、可回滚的建议，不自动执行生产变更。";
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "EVIDENCE_SERIALIZATION_FAILED", "调查证据无法保存");
        }
    }

    private static long elapsedMillis(LocalDateTime start, LocalDateTime end) {
        return Math.max(0, Duration.between(start, end).toMillis());
    }

    private static String normalizeTriggerSource(String source) {
        if (source == null || source.isBlank()) return "INCIDENT_WORKSPACE";
        String normalized = source.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        return normalized.substring(0, Math.min(32, normalized.length()));
    }

    private static String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank()) return null;
        String normalized = key.trim();
        if (normalized.length() > 128) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key 不能超过 128 个字符");
        }
        return normalized;
    }

    private Duration normalizeTimeout(Duration requestedTimeout) {
        Duration timeout = requestedTimeout == null ? defaultExecutionTimeout : requestedTimeout;
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(maxExecutionTimeout) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_TIMEOUT_INVALID",
                    "Agent 超时预算必须大于 0 且不超过 " + maxExecutionTimeout.toSeconds() + " 秒");
        }
        return timeout;
    }

    private static String normalizeTerminationReason(String reason, String fallback) {
        if (reason == null || reason.isBlank()) return fallback;
        String normalized = reason.trim();
        return normalized.substring(0, Math.min(500, normalized.length()));
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String clippedError(RuntimeException exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return message.substring(0, Math.min(980, message.length()));
    }

    private record RuleConclusion(String summary, String hypothesis, BigDecimal confidence, String suggestions) {
    }

    private record FinalizedReport(long reportId, Long proposalId, LocalDateTime finishedAt) {
    }

    private record RunControlState(long incidentId, String status, LocalDateTime deadlineAt,
                                   String terminationKind, String terminationReason,
                                   LocalDateTime startedAt) {
    }

    private record TerminationSignal(String status, String reason) {
    }

    private record TerminationRequest(long incidentId, boolean changed) {
    }

    private static final class RunTerminatedException extends RuntimeException {
        private final TerminationSignal termination;

        private RunTerminatedException(TerminationSignal termination) {
            super(termination.reason());
            this.termination = termination;
        }

        private TerminationSignal termination() {
            return termination;
        }
    }

    public record RunActor(Long userId, String ipAddress) {
    }

    public record PreparedRun(long runId, long incidentId, String triggerSource,
                              String idempotencyKey, LocalDateTime deadlineAt,
                              boolean reused, String status) {
    }

    public record InvestigationResult(Long runId, Long reportId, String status, String engine,
                                      String summary, String hypothesis, BigDecimal confidence,
                                      String suggestions, List<ToolEvidence> evidence,
                                      List<AgentRunQueryService.AgentStepView> steps) {
    }
}
