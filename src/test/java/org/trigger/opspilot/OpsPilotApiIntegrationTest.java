package org.trigger.opspilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.trigger.opspilot.investigation.AgentRunEventService;
import org.trigger.opspilot.investigation.InvestigationService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false"
})
@AutoConfigureMockMvc
class OpsPilotApiIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InvestigationService investigationService;

    @Test
    void shouldReturnStructuredUnauthorizedResponse() throws Exception {
        mockMvc.perform(get("/api/v1/assistant/sessions")
                        .header("Authorization", "Bearer invalid-or-expired-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void shouldAuthenticateAndLoadCoreOperationalViews() throws Exception {
        String token = login("admin", "OpsPilot@2026");

        mockMvc.perform(get("/api/v1/dashboard").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.activeIncidents").isNumber())
                .andExpect(jsonPath("$.data.alertCompressionPercent").isNumber());

        mockMvc.perform(get("/api/v1/cmdb/topology").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes.length()").value(6))
                .andExpect(jsonPath("$.data.edges.length()").value(5));

        mockMvc.perform(get("/api/v1/incidents/1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incident.incidentCode").value("INC-20260819-0001"))
                .andExpect(jsonPath("$.data.alerts.length()").value(2));

        mockMvc.perform(get("/api/v1/observability/providers").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].id").value("prometheus-metrics"))
                .andExpect(jsonPath("$.data[0].available").value(false))
                .andExpect(jsonPath("$.data[1].id").value("local-metrics"))
                .andExpect(jsonPath("$.data[1].circuitState").value("CLOSED"))
                .andExpect(jsonPath("$.data[2].id").value("loki-logs"))
                .andExpect(jsonPath("$.data[2].available").value(false))
                .andExpect(jsonPath("$.data[3].id").value("local-logs"));
    }

    @Test
    void shouldDedupeFingerprintAndAttachBothEventsToOneIncident() throws Exception {
        String payload = """
                {
                  "source": "prometheus",
                  "resourceCode": "APP-PORTAL",
                  "severity": "P4",
                  "title": "门户静态资源错误率升高",
                  "description": "5xx rate 0.8%",
                  "labels": {"cluster": "test-east"}
                }
                """;
        String first = mockMvc.perform(post("/api/v1/alerts/intake")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/v1/alerts/intake")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("DEDUPLICATED"))
                .andReturn().getResponse().getContentAsString();

        JsonNode firstData = objectMapper.readTree(first).path("data");
        JsonNode secondData = objectMapper.readTree(second).path("data");
        org.assertj.core.api.Assertions.assertThat(secondData.path("alertId").asLong())
                .isEqualTo(firstData.path("alertId").asLong());
        org.assertj.core.api.Assertions.assertThat(secondData.path("incidentId").asLong())
                .isEqualTo(firstData.path("incidentId").asLong());
    }

    @Test
    void shouldEnforceRoleAndOptimisticIncidentTransition() throws Exception {
        String adminToken = login("admin", "OpsPilot@2026");
        mockMvc.perform(post("/api/v1/incidents/1/transitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"MITIGATED\",\"version\":2,\"note\":\"测试环境已执行缓解动作\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("MITIGATED"))
                .andExpect(jsonPath("$.data.version").value(3));

        mockMvc.perform(post("/api/v1/incidents/1/transitions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"RESOLVED\",\"version\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INCIDENT_VERSION_CONFLICT"));

        String auditorToken = login("auditor", "OpsPilot@2026");
        mockMvc.perform(post("/api/v1/incidents/2/transitions")
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"CLOSED\",\"version\":4}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldPersistIncidentAwareAssistantConversationAndIsolateUsers() throws Exception {
        String adminToken = login("admin", "OpsPilot@2026");
        String created = mockMvc.perform(post("/api/v1/assistant/sessions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session.incidentCode").value("INC-20260819-0001"))
                .andExpect(jsonPath("$.data.context.alerts.length()").value(2))
                .andExpect(jsonPath("$.data.context.changes.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        long sessionId = objectMapper.readTree(created).path("data").path("session").path("id").asLong();

        mockMvc.perform(post("/api/v1/assistant/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"最可能的根因是什么？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("当前研判")))
                .andExpect(jsonPath("$.data.evidenceJson").value(org.hamcrest.Matchers.containsString("alert:1")))
                .andExpect(jsonPath("$.data.evidenceJson").value(org.hamcrest.Matchers.containsString("change:1")))
                .andExpect(jsonPath("$.data.evidenceJson").value(org.hamcrest.Matchers.containsString("change:2")));

        mockMvc.perform(get("/api/v1/assistant/sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.data.messages[1].role").value("ASSISTANT"));

        mockMvc.perform(get("/api/v1/assistant/sessions/{id}/export", sessionId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("OnCall 助手")));

        MvcResult streamResult = mockMvc.perform(post("/api/v1/assistant/sessions/{id}/stream", sessionId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"下一步应该怎么验证？\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(streamResult))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("\"type\":\"done\"")));

        String auditorToken = login("auditor", "OpsPilot@2026");
        mockMvc.perform(get("/api/v1/assistant/sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + auditorToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ASSISTANT_SESSION_NOT_FOUND"));
    }

    @Test
    void shouldPersistInspectableAgentInvestigationTrace() throws Exception {
        String token = login("admin", "OpsPilot@2026");

        String response = mockMvc.perform(post("/api/v1/incidents/1/investigations")
                        .queryParam("source", "INTEGRATION_TEST")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").isNumber())
                .andExpect(jsonPath("$.data.reportId").isNumber())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.steps.length()").value(9))
                .andExpect(jsonPath("$.data.steps[0].phase").value("PLAN"))
                .andExpect(jsonPath("$.data.steps[1].toolName").value("alert_snapshot"))
                .andExpect(jsonPath("$.data.steps[2].toolName").value("cmdb_topology"))
                .andExpect(jsonPath("$.data.steps[3].toolName").value("metrics_snapshot"))
                .andExpect(jsonPath("$.data.steps[3].inputJson")
                        .value(org.hamcrest.Matchers.containsString("local-metrics")))
                .andExpect(jsonPath("$.data.steps[4].toolName").value("recent_change_correlation"))
                .andExpect(jsonPath("$.data.steps[5].toolName").value("log_search"))
                .andExpect(jsonPath("$.data.steps[5].inputJson")
                        .value(org.hamcrest.Matchers.containsString("redactedFields")))
                .andExpect(jsonPath("$.data.steps[6].toolName").value("runbook_retrieval"))
                .andExpect(jsonPath("$.data.steps[7].phase").value("REPLAN"))
                .andExpect(jsonPath("$.data.steps[8].phase").value("FINISH"))
                .andReturn().getResponse().getContentAsString();
        JsonNode responseData = objectMapper.readTree(response).path("data");
        long runId = responseData.path("runId").asLong();
        org.assertj.core.api.Assertions.assertThat(responseData.path("evidence").toString())
                .contains("METRIC", "LOG", "***")
                .doesNotContain("fake-demo-token-123", "10.20.8.15");

        mockMvc.perform(get("/api/v1/incidents/1/agent-runs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(runId))
                .andExpect(jsonPath("$.data[0].steps.length()").value(9));

        mockMvc.perform(get("/api/v1/incidents/1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agentRuns[0].id").value(runId))
                .andExpect(jsonPath("$.data.agentRuns[0].triggerSource").value("INTEGRATION_TEST"))
                .andExpect(jsonPath("$.data.agentRuns[0].steps.length()").value(9));

        String session = mockMvc.perform(post("/api/v1/assistant/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.context.latestAgentRun.id").value(runId))
                .andExpect(jsonPath("$.data.context.latestAgentRun.steps.length()").value(9))
                .andReturn().getResponse().getContentAsString();
        long sessionId = objectMapper.readTree(session).path("data").path("session").path("id").asLong();

        mockMvc.perform(post("/api/v1/assistant/sessions/{id}/messages", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"展示 Agent 调查过程\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString("执行轨迹")))
                .andExpect(jsonPath("$.data.evidenceJson").value(
                        org.hamcrest.Matchers.containsString("agent-run:" + runId)));
    }

    @Test
    void shouldStreamAndReplayPersistedAgentRunEvents() throws Exception {
        String token = login("zhangwei", "OpsPilot@2026");
        String idempotencyKey = "sse-test-" + UUID.randomUUID();
        MvcResult stream = mockMvc.perform(post("/api/v1/incidents/1/investigations/stream")
                        .queryParam("source", "SSE_INTEGRATION_TEST")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Authorization", "Bearer " + token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();
        String streamBody = mockMvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("\"eventType\":\"RUN_STARTED\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("\"eventType\":\"EVIDENCE_COLLECTED\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("\"eventType\":\"ACTION_PROPOSED\"")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("\"eventType\":\"RUN_COMPLETED\"")))
                .andReturn().getResponse().getContentAsString();
        JsonNode firstEvent = streamBody.lines().filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim()).filter(line -> !line.isBlank())
                .map(line -> {
                    try {
                        return objectMapper.readTree(line);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }).findFirst().orElseThrow();
        long runId = firstEvent.path("runId").asLong();

        String replay = mockMvc.perform(get("/api/v1/agent-runs/{runId}/events", runId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(18))
                .andExpect(jsonPath("$.data[0].eventType").value("RUN_QUEUED"))
                .andExpect(jsonPath("$.data[1].eventType").value("RUN_STARTED"))
                .andExpect(jsonPath("$.data[17].eventType").value("RUN_COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        JsonNode replayEvents = objectMapper.readTree(replay).path("data");
        long after = replayEvents.get(16).path("id").asLong();

        mockMvc.perform(get("/api/v1/agent-runs/{runId}/events", runId)
                        .queryParam("after", String.valueOf(after))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].eventType").value("RUN_COMPLETED"));

        MvcResult duplicate = mockMvc.perform(post("/api/v1/incidents/1/investigations/stream")
                        .queryParam("source", "SSE_INTEGRATION_TEST_RETRY")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Authorization", "Bearer " + token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-OpsPilot-Run-Id", String.valueOf(runId)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-OpsPilot-Idempotent-Replay", "true"))
                .andReturn();
        mockMvc.perform(asyncDispatch(duplicate))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("\"eventType\":\"RUN_COMPLETED\"")));
    }

    @Test
    void shouldPersistRunCancellationTimeoutAndQueueRejection() throws Exception {
        String token = login("admin", "OpsPilot@2026");
        InvestigationService.RunActor actor = new InvestigationService.RunActor(1L, "127.0.0.1");

        InvestigationService.PreparedRun cancellable = investigationService.prepare(
                1, "CONTROL_TEST", actor, "cancel-" + UUID.randomUUID(), Duration.ofSeconds(30));
        mockMvc.perform(post("/api/v1/agent-runs/{runId}/cancel", cancellable.runId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"值班人员终止重复调查\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.terminationKind").value("CANCEL"));
        mockMvc.perform(get("/api/v1/agent-runs/{runId}/events", cancellable.runId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].eventType").value("RUN_CANCELLED"));

        InvestigationService.PreparedRun timed = investigationService.prepare(
                1, "TIMEOUT_TEST", actor, "timeout-" + UUID.randomUUID(), Duration.ofMillis(1));
        while (LocalDateTime.now().isBefore(timed.deadlineAt())) Thread.onSpinWait();
        InvestigationService.InvestigationResult timedResult = investigationService.execute(
                timed, actor, AgentRunEventService.EventSink.NOOP);
        org.assertj.core.api.Assertions.assertThat(timedResult.status()).isEqualTo("TIMED_OUT");
        org.assertj.core.api.Assertions.assertThat(timedResult.reportId()).isNull();

        InvestigationService.PreparedRun rejected = investigationService.prepare(
                1, "QUEUE_TEST", actor, "queue-" + UUID.randomUUID(), Duration.ofSeconds(30));
        investigationService.rejectQueue(rejected.runId(),
                new TaskRejectedException("saturated for test"), AgentRunEventService.EventSink.NOOP);
        org.assertj.core.api.Assertions.assertThat(investigationService.listRuns(1).stream()
                        .filter(run -> run.id().equals(rejected.runId())).findFirst().orElseThrow().status())
                .isEqualTo("QUEUE_REJECTED");
        mockMvc.perform(get("/api/v1/agent-runs/{runId}/events", rejected.runId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].eventType").value("RUN_REJECTED"));
    }

    @Test
    void shouldRequireIndependentVersionedReviewForHighRiskProposal() throws Exception {
        String onCallToken = login("zhangwei", "OpsPilot@2026");
        String runResponse = mockMvc.perform(post("/api/v1/incidents/1/investigations")
                        .queryParam("source", "APPROVAL_INTEGRATION_TEST")
                        .header("Authorization", "Bearer " + onCallToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long runId = objectMapper.readTree(runResponse).path("data").path("runId").asLong();
        String proposalsResponse = mockMvc.perform(get("/api/v1/incidents/1/remediation-proposals")
                        .header("Authorization", "Bearer " + onCallToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode proposal = proposalForRun(objectMapper.readTree(proposalsResponse).path("data"), runId);
        long proposalId = proposal.path("id").asLong();
        org.assertj.core.api.Assertions.assertThat(proposal.path("status").asText())
                .isEqualTo("PENDING_APPROVAL");
        org.assertj.core.api.Assertions.assertThat(proposal.path("requestedById").asLong()).isEqualTo(2);

        String auditorToken = login("auditor", "OpsPilot@2026");
        mockMvc.perform(post("/api/v1/remediation-proposals/{id}/reviews", proposalId)
                        .header("Authorization", "Bearer " + auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"version\":1,\"comment\":\"审计尝试审批\"}"))
                .andExpect(status().isForbidden());

        String managerToken = login("lina", "OpsPilot@2026");
        mockMvc.perform(post("/api/v1/remediation-proposals/{id}/reviews", proposalId)
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"version\":99,\"comment\":\"错误版本验证\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REMEDIATION_VERSION_CONFLICT"));

        mockMvc.perform(post("/api/v1/remediation-proposals/{id}/reviews", proposalId)
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"version\":1,\"comment\":\"已核对回滚窗口与负责人\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.reviewedById").value(3));

        String adminToken = login("admin", "OpsPilot@2026");
        String adminRun = mockMvc.perform(post("/api/v1/incidents/1/investigations")
                        .queryParam("source", "SELF_APPROVAL_TEST")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long adminRunId = objectMapper.readTree(adminRun).path("data").path("runId").asLong();
        String latestProposals = mockMvc.perform(get("/api/v1/incidents/1/remediation-proposals")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode adminProposal = proposalForRun(
                objectMapper.readTree(latestProposals).path("data"), adminRunId);
        mockMvc.perform(post("/api/v1/remediation-proposals/{id}/reviews", adminProposal.path("id").asLong())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"version\":1,\"comment\":\"尝试自我审批\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACTION_SELF_APPROVAL_FORBIDDEN"));
    }

    private static JsonNode proposalForRun(JsonNode proposals, long runId) {
        for (JsonNode proposal : proposals) {
            if (proposal.path("runId").asLong() == runId) return proposal;
        }
        throw new IllegalStateException("Proposal not found for run " + runId);
    }

    private String login(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("accessToken").asText();
    }
}
