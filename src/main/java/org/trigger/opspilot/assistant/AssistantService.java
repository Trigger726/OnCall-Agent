package org.trigger.opspilot.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.investigation.AgentRunQueryService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class AssistantService {
    private static final int HISTORY_LIMIT = 12;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final Optional<AssistantAiService> aiService;
    private final AgentRunQueryService agentRunQueryService;

    public AssistantService(JdbcClient jdbcClient, ObjectMapper objectMapper,
                            Optional<AssistantAiService> aiService,
                            AgentRunQueryService agentRunQueryService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.aiService = aiService;
        this.agentRunQueryService = agentRunQueryService;
    }

    public List<SessionSummary> listSessions(long ownerId) {
        return jdbcClient.sql("""
                        SELECT s.id, s.title, s.incident_id, s.updated_at,
                               i.incident_code, i.title AS incident_title, i.severity, i.status AS incident_status,
                               (SELECT COUNT(*) FROM assistant_message count_message WHERE count_message.session_id = s.id) AS message_count,
                               (SELECT last_message.content FROM assistant_message last_message
                                WHERE last_message.session_id = s.id ORDER BY last_message.id DESC LIMIT 1) AS last_message
                        FROM assistant_session s
                        LEFT JOIN incident i ON i.id = s.incident_id
                        WHERE s.owner_user_id = :ownerId AND s.status = 'ACTIVE'
                        ORDER BY s.updated_at DESC, s.id DESC
                        """)
                .param("ownerId", ownerId)
                .query(AssistantService::mapSession).list();
    }

    @Transactional
    public SessionDetail createSession(long ownerId, Long incidentId, String requestedTitle) {
        IncidentContext context = incidentId == null ? null : loadIncidentContext(incidentId);
        String title = normalizeTitle(requestedTitle, context);
        jdbcClient.sql("""
                        INSERT INTO assistant_session(owner_user_id, incident_id, title)
                        VALUES (:ownerId, :incidentId, :title)
                        """)
                .param("ownerId", ownerId).param("incidentId", incidentId).param("title", title).update();
        Long sessionId = jdbcClient.sql("""
                        SELECT id FROM assistant_session WHERE owner_user_id = :ownerId
                        ORDER BY id DESC LIMIT 1
                        """)
                .param("ownerId", ownerId).query(Long.class).single();
        recordAudit(ownerId, "ASSISTANT_SESSION_CREATE", sessionId,
                incidentId == null ? "新建通用 OnCall 对话" : "绑定 Incident #" + incidentId);
        return getSession(sessionId, ownerId);
    }

    public SessionDetail getSession(long sessionId, long ownerId) {
        SessionSummary session = findSession(sessionId, ownerId);
        List<MessageView> messages = jdbcClient.sql("""
                        SELECT id, role, content, evidence_json, created_at
                        FROM assistant_message WHERE session_id = :sessionId ORDER BY id
                        """)
                .param("sessionId", sessionId).query(AssistantService::mapMessage).list();
        IncidentContext context = session.incidentId() == null ? null : loadIncidentContext(session.incidentId());
        return new SessionDetail(session, messages, context);
    }

    public MessageView sendMessage(long sessionId, long ownerId, String rawContent) {
        SessionSummary session = findSession(sessionId, ownerId);
        String question = rawContent == null ? "" : rawContent.trim();
        if (question.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSISTANT_MESSAGE_EMPTY", "消息内容不能为空");
        }
        if (question.length() > 10000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSISTANT_MESSAGE_TOO_LONG", "单条消息不能超过 10000 字");
        }

        insertMessage(sessionId, "USER", question, null);
        List<MessageView> history = recentMessages(sessionId);
        IncidentContext context = session.incidentId() == null ? null : loadIncidentContext(session.incidentId());
        Answer answer = generateAnswer(context, history, question);
        long messageId = insertMessage(sessionId, "ASSISTANT", answer.content(), serializeEvidence(answer.evidence()));

        String newTitle = session.messageCount() == 0 && isDefaultTitle(session.title())
                ? titleFromQuestion(question) : session.title();
        jdbcClient.sql("""
                        UPDATE assistant_session SET title = :title, updated_at = CURRENT_TIMESTAMP WHERE id = :id
                        """)
                .param("title", newTitle).param("id", sessionId).update();
        recordAudit(ownerId, "ASSISTANT_MESSAGE", sessionId,
                "OnCall 助手完成回答，证据引用 " + answer.evidence().size() + " 项");
        return jdbcClient.sql("""
                        SELECT id, role, content, evidence_json, created_at
                        FROM assistant_message WHERE id = :id
                        """)
                .param("id", messageId).query(AssistantService::mapMessage).single();
    }

    @Transactional
    public void clearMessages(long sessionId, long ownerId) {
        findSession(sessionId, ownerId);
        jdbcClient.sql("DELETE FROM assistant_message WHERE session_id = :sessionId")
                .param("sessionId", sessionId).update();
        jdbcClient.sql("UPDATE assistant_session SET updated_at = CURRENT_TIMESTAMP WHERE id = :sessionId")
                .param("sessionId", sessionId).update();
        recordAudit(ownerId, "ASSISTANT_SESSION_CLEAR", sessionId, "清空 OnCall 对话消息");
    }

    @Transactional
    public void deleteSession(long sessionId, long ownerId) {
        findSession(sessionId, ownerId);
        jdbcClient.sql("DELETE FROM assistant_session WHERE id = :sessionId")
                .param("sessionId", sessionId).update();
        recordAudit(ownerId, "ASSISTANT_SESSION_DELETE", sessionId, "删除 OnCall 对话");
    }

    public String exportMarkdown(long sessionId, long ownerId) {
        SessionDetail detail = getSession(sessionId, ownerId);
        StringBuilder markdown = new StringBuilder("# ").append(detail.session().title()).append("\n\n");
        if (detail.context() != null) {
            markdown.append("> Incident: ").append(detail.context().incidentCode())
                    .append(" · ").append(detail.context().title()).append("\n\n");
        }
        for (MessageView message : detail.messages()) {
            markdown.append("## ").append("USER".equals(message.role()) ? "值班工程师" : "OnCall 助手")
                    .append("\n\n").append(message.content()).append("\n\n")
                    .append("_时间：").append(message.createdAt()).append("_\n\n");
        }
        return markdown.toString();
    }

    private Answer generateAnswer(IncidentContext context, List<MessageView> history, String question) {
        List<EvidenceRef> evidence = context == null ? List.of() : collectEvidence(context);
        if (aiService.isPresent()) {
            try {
                String content = aiService.get().answer(contextText(context), conversationText(history), question);
                if (content != null && !content.isBlank()) {
                    return new Answer(content.trim(), evidence);
                }
            } catch (RuntimeException ignored) {
                // The deterministic evidence mode remains available when the provider is unavailable.
            }
        }
        return new Answer(ruleBasedAnswer(context, question), evidence);
    }

    private String ruleBasedAnswer(IncidentContext context, String question) {
        if (context == null) {
            List<String> active = jdbcClient.sql("""
                            SELECT incident_code, title, severity, status FROM incident
                            WHERE status NOT IN ('RESOLVED', 'CLOSED')
                            ORDER BY CASE severity WHEN 'P1' THEN 1 WHEN 'P2' THEN 2 ELSE 3 END, updated_at DESC
                            LIMIT 5
                            """)
                    .query((rs, rowNum) -> "- **" + rs.getString("incident_code") + "** "
                            + rs.getString("title") + "（" + rs.getString("severity") + " / "
                            + rs.getString("status") + "）").list();
            return "我是 OpsPilot OnCall 助手，可以围绕告警、Incident、CMDB、变更和 Runbook 持续协作。\n\n"
                    + (active.isEmpty() ? "当前没有活跃 Incident。" : "### 当前活跃 Incident\n" + String.join("\n", active))
                    + "\n\n将会话绑定到某个 Incident 后，可以继续问：‘总结当前证据’、‘最可能的根因是什么’或‘下一步怎么验证’。";
        }

        String normalized = question.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "调查过程", "agent", "工具", "执行轨迹", "怎么查")) {
            if (context.latestAgentRun() == null) {
                return "当前 Incident 还没有 Agent 调查运行。可以在右侧上下文栏启动一次只读取证，系统会记录每个工具的状态、证据和耗时。";
            }
            AgentRunQueryService.AgentRunView run = context.latestAgentRun();
            String steps = run.steps().stream()
                    .map(step -> "- **" + step.phase() + " / " + step.status() + "** " + step.title()
                            + "：" + step.outputSummary())
                    .reduce((left, right) -> left + "\n" + right).orElse("暂无执行步骤。");
            return "### Agent 调查 #" + run.id() + "\n- 状态：**" + run.status() + "**\n- 耗时："
                    + (run.durationMs() == null ? "-" : run.durationMs() + " ms")
                    + "\n\n### 执行轨迹\n" + steps
                    + "\n\n所有步骤均为只读查询，建议动作不会自动作用于生产环境。";
        }
        if (containsAny(normalized, "根因", "原因", "为什么", "判断")) {
            String hypothesis = context.latestHypothesis() == null ? "现有证据还不足以形成稳定根因假设。" : context.latestHypothesis();
            return "### 当前研判\n" + hypothesis + "\n\n### 证据边界\n"
                    + evidenceSummary(context) + "\n\n该结论仍需通过指标恢复情况或回滚验证，不能直接视为最终根因。";
        }
        if (containsAny(normalized, "变更", "发布", "回滚")) {
            if (context.changes().isEmpty()) return "当前资源没有检索到近期变更记录，暂时不能建立变更关联。";
            return "### 近期变更\n" + context.changes().stream()
                    .map(item -> "- **" + item.code() + "** " + item.title() + "（" + item.time() + "）")
                    .reduce((left, right) -> left + "\n" + right).orElse("")
                    + "\n\n建议先核对变更前后指标，再决定是否回滚；不要只依据时间相邻直接认定因果。";
        }
        if (containsAny(normalized, "告警", "指标", "现象")) {
            return "### 关联告警\n" + (context.alerts().isEmpty() ? "暂无关联告警。" : context.alerts().stream()
                    .map(item -> "- **" + item.code() + "** " + item.title())
                    .reduce((left, right) -> left + "\n" + right).orElse(""))
                    + "\n\n告警只代表观测现象，需要和变更、依赖及恢复验证结合。";
        }
        if (containsAny(normalized, "进展", "状态", "时间线", "谁在处理")) {
            return "### 当前状态\n- Incident：**" + context.severity() + " / " + context.status() + "**\n- 影响服务：**"
                    + context.resourceName() + "**\n\n### 最近进展\n"
                    + context.timeline().stream().map(item -> "- " + item.time() + " · " + item.title())
                    .reduce((left, right) -> left + "\n" + right).orElse("暂无处置记录。");
        }
        if (containsAny(normalized, "下一步", "怎么办", "建议", "处理", "处置", "验证")) {
            String suggestions = context.latestSuggestions() == null
                    ? "1. 核对告警指标与影响范围；\n2. 检查近期变更和下游依赖；\n3. 选择可回滚的最小动作并持续观察。"
                    : context.latestSuggestions();
            return "### 建议动作\n" + suggestions + "\n\n### 执行原则\n先验证、再操作；优先可回滚动作；执行后同时观察业务指标和关联告警。";
        }
        return "### Incident 概况\n- **" + context.incidentCode() + "** " + context.title()
                + "\n- 状态：" + context.severity() + " / " + context.status()
                + "\n- 影响服务：" + context.resourceName()
                + "\n- 关联告警：" + context.alerts().size() + " 条"
                + "\n\n### 当前证据\n" + evidenceSummary(context)
                + "\n\n你可以继续追问根因、近期变更、处置进展或下一步验证动作。";
    }

    private IncidentContext loadIncidentContext(long incidentId) {
        IncidentBase base = jdbcClient.sql("""
                        SELECT i.id, i.incident_code, i.title, i.description, i.severity, i.status,
                               i.service_resource_id, r.name AS resource_name
                        FROM incident i JOIN cmdb_resource r ON r.id = i.service_resource_id
                        WHERE i.id = :incidentId
                        """)
                .param("incidentId", incidentId)
                .query((rs, rowNum) -> new IncidentBase(
                        rs.getLong("id"), rs.getString("incident_code"), rs.getString("title"),
                        rs.getString("description"), rs.getString("severity"), rs.getString("status"),
                        rs.getLong("service_resource_id"), rs.getString("resource_name")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "INCIDENT_NOT_FOUND", "Incident 不存在"));
        List<ContextItem> alerts = jdbcClient.sql("""
                        SELECT id, severity, title, last_occurred_at FROM alert_event
                        WHERE incident_id = :incidentId ORDER BY last_occurred_at DESC
                        """)
                .param("incidentId", incidentId).query((rs, rowNum) -> new ContextItem(
                        "alert:" + rs.getLong("id"), rs.getString("severity"), rs.getString("title"),
                        rs.getObject("last_occurred_at", LocalDateTime.class))).list();
        List<ContextItem> changes = jdbcClient.sql("""
                        SELECT id, change_code, summary, started_at FROM change_record
                        WHERE resource_id = :resourceId
                           OR resource_id IN (
                               SELECT target_resource_id FROM cmdb_relation WHERE source_resource_id = :resourceId
                           )
                        ORDER BY started_at DESC LIMIT 5
                        """)
                .param("resourceId", base.resourceId()).query((rs, rowNum) -> new ContextItem(
                        "change:" + rs.getLong("id"), rs.getString("change_code"), rs.getString("summary"),
                        rs.getObject("started_at", LocalDateTime.class))).list();
        List<ContextItem> timeline = jdbcClient.sql("""
                        SELECT id, event_type, content, created_at FROM incident_timeline
                        WHERE incident_id = :incidentId ORDER BY created_at DESC, id DESC LIMIT 8
                        """)
                .param("incidentId", incidentId).query((rs, rowNum) -> new ContextItem(
                        "timeline:" + rs.getLong("id"), rs.getString("event_type"), rs.getString("content"),
                        rs.getObject("created_at", LocalDateTime.class))).list();
        Investigation investigation = jdbcClient.sql("""
                        SELECT id, hypothesis, suggestions FROM investigation_report
                        WHERE incident_id = :incidentId ORDER BY created_at DESC, id DESC LIMIT 1
                        """)
                .param("incidentId", incidentId).query((rs, rowNum) -> new Investigation(
                        rs.getLong("id"), rs.getString("hypothesis"), rs.getString("suggestions")))
                .optional().orElse(null);
        AgentRunQueryService.AgentRunView latestAgentRun = agentRunQueryService.listByIncident(incidentId)
                .stream().findFirst().orElse(null);
        return new IncidentContext(base.id(), base.incidentCode(), base.title(), base.description(),
                base.severity(), base.status(), base.resourceName(), alerts, changes, timeline,
                investigation == null ? null : investigation.id(),
                investigation == null ? null : investigation.hypothesis(),
                investigation == null ? null : investigation.suggestions(), latestAgentRun);
    }

    private SessionSummary findSession(long sessionId, long ownerId) {
        return jdbcClient.sql("""
                        SELECT s.id, s.title, s.incident_id, s.updated_at,
                               i.incident_code, i.title AS incident_title, i.severity, i.status AS incident_status,
                               (SELECT COUNT(*) FROM assistant_message count_message WHERE count_message.session_id = s.id) AS message_count,
                               (SELECT last_message.content FROM assistant_message last_message
                                WHERE last_message.session_id = s.id ORDER BY last_message.id DESC LIMIT 1) AS last_message
                        FROM assistant_session s LEFT JOIN incident i ON i.id = s.incident_id
                        WHERE s.id = :sessionId AND s.owner_user_id = :ownerId AND s.status = 'ACTIVE'
                        """)
                .param("sessionId", sessionId).param("ownerId", ownerId)
                .query(AssistantService::mapSession).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "ASSISTANT_SESSION_NOT_FOUND", "会话不存在或无权访问"));
    }

    private List<MessageView> recentMessages(long sessionId) {
        List<MessageView> descending = jdbcClient.sql("""
                        SELECT id, role, content, evidence_json, created_at FROM assistant_message
                        WHERE session_id = :sessionId ORDER BY id DESC LIMIT :limit
                        """)
                .param("sessionId", sessionId).param("limit", HISTORY_LIMIT)
                .query(AssistantService::mapMessage).list();
        Collections.reverse(descending);
        return descending;
    }

    private long insertMessage(long sessionId, String role, String content, String evidenceJson) {
        jdbcClient.sql("""
                        INSERT INTO assistant_message(session_id, role, content, evidence_json)
                        VALUES (:sessionId, :role, :content, :evidenceJson)
                        """)
                .param("sessionId", sessionId).param("role", role)
                .param("content", content).param("evidenceJson", evidenceJson).update();
        return jdbcClient.sql("SELECT id FROM assistant_message WHERE session_id = :sessionId ORDER BY id DESC LIMIT 1")
                .param("sessionId", sessionId).query(Long.class).single();
    }

    private void recordAudit(long ownerId, String action, long sessionId, String detail) {
        jdbcClient.sql("""
                        INSERT INTO audit_log(actor_id, action, target_type, target_id, detail, ip_address)
                        VALUES (:actorId, :action, 'ASSISTANT_SESSION', :targetId, :detail, 'assistant')
                        """)
                .param("actorId", ownerId).param("action", action)
                .param("targetId", String.valueOf(sessionId)).param("detail", detail).update();
    }

    private List<EvidenceRef> collectEvidence(IncidentContext context) {
        List<EvidenceRef> refs = new ArrayList<>();
        refs.add(new EvidenceRef("incident:" + context.id(), "INCIDENT", context.incidentCode()));
        context.alerts().forEach(item -> refs.add(new EvidenceRef(item.code(), "ALERT", item.title())));
        context.changes().forEach(item -> refs.add(new EvidenceRef(item.code(), "CHANGE", item.title())));
        if (context.latestInvestigationId() != null) {
            refs.add(new EvidenceRef("investigation:" + context.latestInvestigationId(),
                    "INVESTIGATION", "最近调查报告"));
        }
        if (context.latestAgentRun() != null) {
            refs.add(new EvidenceRef("agent-run:" + context.latestAgentRun().id(),
                    "AGENT_RUN", "Agent 调查执行轨迹"));
        }
        return refs;
    }

    private String serializeEvidence(List<EvidenceRef> evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private String contextText(IncidentContext context) {
        if (context == null) return "当前会话未绑定 Incident。";
        return "Incident=" + context.incidentCode() + " " + context.title()
                + "\n状态=" + context.severity() + "/" + context.status()
                + "\n影响服务=" + context.resourceName()
                + "\n描述=" + context.description()
                + "\n告警=" + context.alerts()
                + "\n变更=" + context.changes()
                + "\n时间线=" + context.timeline()
                + "\nAgent调查=" + context.latestAgentRun()
                + "\n最近假设=" + context.latestHypothesis()
                + "\n建议=" + context.latestSuggestions();
    }

    private String conversationText(List<MessageView> history) {
        return history.stream().map(message -> message.role() + ": " + message.content())
                .reduce((left, right) -> left + "\n" + right).orElse("无历史消息");
    }

    private String evidenceSummary(IncidentContext context) {
        List<String> lines = new ArrayList<>();
        context.alerts().stream().limit(3).forEach(item -> lines.add("- [" + item.code() + "] " + item.title()));
        context.changes().stream().limit(2).forEach(item -> lines.add("- [" + item.code() + "] " + item.title()));
        if (context.latestInvestigationId() != null) lines.add("- [investigation:" + context.latestInvestigationId() + "] 最近调查报告");
        if (context.latestAgentRun() != null) lines.add("- [agent-run:" + context.latestAgentRun().id() + "] Agent 调查执行轨迹");
        return lines.isEmpty() ? "暂无可引用证据。" : String.join("\n", lines);
    }

    private static boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) if (value.contains(keyword)) return true;
        return false;
    }

    private static String normalizeTitle(String requestedTitle, IncidentContext context) {
        if (requestedTitle != null && !requestedTitle.isBlank()) return requestedTitle.trim().substring(0, Math.min(160, requestedTitle.trim().length()));
        return context == null ? "新对话" : context.incidentCode() + " 协作分析";
    }

    private static String titleFromQuestion(String question) {
        String compact = question.replaceAll("\\s+", " ").trim();
        return compact.substring(0, Math.min(32, compact.length()));
    }

    private static boolean isDefaultTitle(String title) {
        return "新对话".equals(title);
    }

    private static SessionSummary mapSession(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        String lastMessage = rs.getString("last_message");
        if (lastMessage != null) {
            lastMessage = lastMessage.replaceAll("(?m)^#{1,3}\\s*", "")
                    .replace("**", "").replaceAll("\\s+", " ").trim();
            if (lastMessage.length() > 80) lastMessage = lastMessage.substring(0, 80) + "...";
        }
        return new SessionSummary(rs.getLong("id"), rs.getString("title"),
                rs.getObject("incident_id", Long.class), rs.getString("incident_code"),
                rs.getString("incident_title"), rs.getString("severity"), rs.getString("incident_status"),
                rs.getInt("message_count"), lastMessage, rs.getObject("updated_at", LocalDateTime.class));
    }

    private static MessageView mapMessage(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new MessageView(rs.getLong("id"), rs.getString("role"), rs.getString("content"),
                rs.getString("evidence_json"), rs.getObject("created_at", LocalDateTime.class));
    }

    public record SessionSummary(Long id, String title, Long incidentId, String incidentCode,
                                 String incidentTitle, String incidentSeverity, String incidentStatus,
                                 int messageCount, String lastMessage, LocalDateTime updatedAt) {
    }

    public record SessionDetail(SessionSummary session, List<MessageView> messages, IncidentContext context) {
    }

    public record MessageView(Long id, String role, String content, String evidenceJson, LocalDateTime createdAt) {
    }

    public record IncidentContext(Long id, String incidentCode, String title, String description,
                                  String severity, String status, String resourceName,
                                  List<ContextItem> alerts, List<ContextItem> changes, List<ContextItem> timeline,
                                  Long latestInvestigationId, String latestHypothesis, String latestSuggestions,
                                  AgentRunQueryService.AgentRunView latestAgentRun) {
    }

    public record ContextItem(String code, String type, String title, LocalDateTime time) {
    }

    public record EvidenceRef(String ref, String type, String label) {
    }

    private record IncidentBase(Long id, String incidentCode, String title, String description,
                                String severity, String status, Long resourceId, String resourceName) {
    }

    private record Investigation(Long id, String hypothesis, String suggestions) {
    }

    private record Answer(String content, List<EvidenceRef> evidence) {
    }
}
