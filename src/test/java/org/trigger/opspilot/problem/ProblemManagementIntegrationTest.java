package org.trigger.opspilot.problem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-problem-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class ProblemManagementIntegrationTest {
    private static final String FROM = "2026-08-01";
    private static final String TO = "2026-09-30";
    private static final String SIGNAL_TITLE = "Token refresh failures";
    private static final String FINGERPRINT = fingerprint(
            "prometheus", 3, "P2", SIGNAL_TITLE);
    private static final String OTHER_FINGERPRINT = "b".repeat(64);
    private static final String RECURRENCE_KEY = "3:" + FINGERPRINT;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void shouldServeEverySpaRouteThroughOneUnambiguousForwarder() throws Exception {
        for (String route : new String[]{
                "/login", "/incidents", "/assistant", "/alerts", "/cmdb",
                "/on-call", "/runbooks", "/analytics", "/problems", "/audit"
        }) {
            mockMvc.perform(get(route))
                    .andExpect(status().isOk())
                    .andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void shouldPromoteMaximumLengthEvidenceWithoutLosingOriginalTitles() throws Exception {
        seedRecurringIncidents();
        String serviceName = "服".repeat(128);
        String signalTitle = "警".repeat(240);
        String longFingerprint = fingerprint("prometheus", 3, "P2", signalTitle);
        jdbcClient.sql("UPDATE cmdb_resource SET name = :name WHERE id = 3")
                .param("name", serviceName).update();
        jdbcClient.sql("UPDATE alert_event SET title = :title, fingerprint = :longFingerprint "
                        + "WHERE fingerprint = :originalFingerprint")
                .param("title", signalTitle).param("longFingerprint", longFingerprint)
                .param("originalFingerprint", FINGERPRINT).update();
        String manager = login("lina");
        JsonNode created = data(post("/api/v1/problems")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "recurrenceKey", "3:" + longFingerprint, "from", FROM, "to", TO))), manager);
        assertThat(created.path("created").asBoolean()).isTrue();
        assertThat(created.path("problem").path("title").asText())
                .hasSize(240).startsWith(serviceName + " 重复故障：").endsWith("…");
        assertThat(created.path("newlyLinkedIncidents").asInt()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT title FROM alert_event WHERE fingerprint = :fingerprint")
                .param("fingerprint", longFingerprint).query(String.class).list())
                .containsExactly(signalTitle, signalTitle);
    }

    @Test
    void shouldTurnExactCrossIncidentRecurrenceIntoAuditedProblemLifecycle() throws Exception {
        seedRecurringIncidents();
        String manager = login("lina");
        String onCall = login("zhangwei");

        JsonNode candidates = data(get("/api/v1/problems/recurrence-candidates")
                .param("from", FROM).param("to", TO), onCall);
        assertThat(candidates.path("total").asLong()).isEqualTo(1);
        JsonNode candidate = candidates.path("items").get(0);
        assertThat(candidate.path("recurrenceKey").asText()).isEqualTo(RECURRENCE_KEY);
        assertThat(candidate.path("matchReason").asText()).isEqualTo("EXACT_ALERT_FINGERPRINT");
        assertThat(candidate.path("incidentCount").asLong()).isEqualTo(2);
        assertThat(candidate.path("distinctDays").asLong()).isEqualTo(2);
        assertThat(candidate.path("totalAlertOccurrences").asLong()).isEqualTo(102);
        assertThat(candidate.path("incidents").size()).isEqualTo(2);

        mockMvc.perform(post("/api/v1/problems")
                        .header("Authorization", bearer(onCall))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isForbidden());

        JsonNode created = data(post("/api/v1/problems")
                .contentType(MediaType.APPLICATION_JSON).content(createBody()), manager);
        assertThat(created.path("created").asBoolean()).isTrue();
        assertThat(created.path("newlyLinkedIncidents").asInt()).isEqualTo(2);
        JsonNode problem = created.path("problem");
        long problemId = problem.path("id").asLong();
        assertThat(problem.path("status").asText()).isEqualTo("OPEN");
        assertThat(problem.path("incidentCount").asLong()).isEqualTo(2);
        assertThat(problem.path("version").asInt()).isZero();

        JsonNode repeated = data(post("/api/v1/problems")
                .contentType(MediaType.APPLICATION_JSON).content(createBody()), manager);
        assertThat(repeated.path("created").asBoolean()).isFalse();
        assertThat(repeated.path("newlyLinkedIncidents").asInt()).isZero();
        assertThat(repeated.path("problem").path("id").asLong()).isEqualTo(problemId);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM problem_record")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM problem_incident_link")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM incident_timeline WHERE event_type = 'PROBLEM_LINKED'
                        """).query(Long.class).single()).isEqualTo(2);

        mockMvc.perform(patch("/api/v1/problems/{id}", problemId)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0, "title", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PROBLEM_TITLE_REQUIRED"));

        mockMvc.perform(patch("/api/v1/problems/{id}", problemId)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0, "status", "KNOWN_ERROR"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PROBLEM_KNOWN_ERROR_INCOMPLETE"));

        JsonNode knownError = data(patch("/api/v1/problems/{id}", problemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "expectedVersion", 0,
                        "status", "KNOWN_ERROR",
                        "rootCause", "旧客户端重复使用已失效 refresh token",
                        "workaround", "清理本地凭证并重新登录"))), manager);
        assertThat(knownError.path("status").asText()).isEqualTo("KNOWN_ERROR");
        assertThat(knownError.path("version").asInt()).isEqualTo(1);

        mockMvc.perform(patch("/api/v1/problems/{id}", problemId)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 0, "status", "RESOLVED",
                                "resolutionSummary", "错误的并发版本"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PROBLEM_VERSION_CONFLICT"));

        JsonNode resolved = data(patch("/api/v1/problems/{id}", problemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "expectedVersion", 1, "status", "RESOLVED",
                        "resolutionSummary", "服务端完成 refresh token 轮换兼容"))), manager);
        assertThat(resolved.path("status").asText()).isEqualTo("RESOLVED");
        assertThat(resolved.path("version").asInt()).isEqualTo(2);
        assertThat(resolved.path("recurredAfterResolution").asBoolean()).isFalse();

        mockMvc.perform(patch("/api/v1/problems/{id}", problemId)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 2, "status", "KNOWN_ERROR"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PROBLEM_INVALID_TRANSITION"));

        addFutureIncident();
        String intake = objectMapper.writeValueAsString(Map.of(
                "source", "prometheus", "externalEventId", "problem-future-203",
                "resourceCode", "APP-AUTH", "severity", "P2", "status", "FIRING",
                "title", SIGNAL_TITLE, "description", "解决后再次出现", "labels", Map.of()));
        mockMvc.perform(post("/api/v1/alerts/intake")
                        .contentType(MediaType.APPLICATION_JSON).content(intake))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incidentId").value(203));
        mockMvc.perform(post("/api/v1/alerts/intake")
                        .contentType(MediaType.APPLICATION_JSON).content(intake))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("DEDUPLICATED"));

        JsonNode recurred = data(get("/api/v1/problems/{id}", problemId), manager);
        assertThat(recurred.path("status").asText()).isEqualTo("RESOLVED");
        assertThat(recurred.path("incidentCount").asLong()).isEqualTo(3);
        assertThat(recurred.path("activeIncidentCount").asLong()).isEqualTo(1);
        assertThat(recurred.path("recurredAfterResolution").asBoolean()).isTrue();
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM problem_incident_link")
                .query(Long.class).single()).isEqualTo(3);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM incident_timeline WHERE event_type = 'PROBLEM_LINKED'
                        """).query(Long.class).single()).isEqualTo(3);

        mockMvc.perform(patch("/api/v1/problems/{id}", problemId)
                        .header("Authorization", bearer(onCall))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", 2, "status", "OPEN"))))
                .andExpect(status().isForbidden());
        JsonNode reopened = data(patch("/api/v1/problems/{id}", problemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "expectedVersion", 2, "status", "OPEN"))), manager);
        assertThat(reopened.path("status").asText()).isEqualTo("OPEN");
        assertThat(reopened.path("resolvedAt").isNull()).isTrue();
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM audit_log WHERE target_type = 'PROBLEM'
                        """).query(Long.class).single()).isEqualTo(4);
    }

    private void seedRecurringIncidents() {
        jdbcClient.sql("""
                        INSERT INTO incident(
                          id, incident_code, title, severity, status, service_resource_id,
                          acknowledged_at, resolved_at, created_at, updated_at)
                        VALUES
                          (201, 'INC-PROBLEM-201', '首次 token 刷新失败', 'P2', 'RESOLVED', 3,
                           '2026-08-01 10:05:00', '2026-08-01 10:40:00',
                           '2026-08-01 10:00:00', '2026-08-01 10:40:00'),
                          (202, 'INC-PROBLEM-202', '再次 token 刷新失败', 'P2', 'CLOSED', 3,
                           '2026-08-12 11:04:00', '2026-08-12 11:30:00',
                           '2026-08-12 11:00:00', '2026-08-12 11:35:00')
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO alert_event(
                          source, external_event_id, fingerprint, service_resource_id,
                          severity, status, title, description, labels_json,
                          first_occurred_at, last_occurred_at, occurrence_count, incident_id)
                        VALUES
                          ('prometheus', 'problem-201-a', :fingerprint, 3, 'P2', 'RESOLVED',
                           :title, '首次', '{}', '2026-08-01 10:00:00', '2026-08-01 10:20:00', 100, 201),
                          ('prometheus', 'problem-202-a', :fingerprint, 3, 'P2', 'RESOLVED',
                           :title, '再次', '{}', '2026-08-12 11:00:00', '2026-08-12 11:10:00', 2, 202),
                          ('prometheus', 'noise-201-a', :otherFingerprint, 3, 'P3', 'RESOLVED',
                           '单事故噪声一', '{}', '{}', '2026-08-01 10:00:00', '2026-08-01 10:05:00', 50, 201),
                          ('prometheus', 'noise-201-b', :otherFingerprint, 3, 'P3', 'RESOLVED',
                           '单事故噪声二', '{}', '{}', '2026-08-01 10:06:00', '2026-08-01 10:08:00', 60, 201)
                        """).param("fingerprint", FINGERPRINT).param("title", SIGNAL_TITLE)
                .param("otherFingerprint", OTHER_FINGERPRINT).update();
    }

    private void addFutureIncident() {
        LocalDateTime createdAt = LocalDateTime.now().plusMinutes(1);
        jdbcClient.sql("""
                        INSERT INTO incident(
                          id, incident_code, title, severity, status, service_resource_id,
                          created_at, updated_at)
                        VALUES (203, 'INC-PROBLEM-203', '解决后 token 再次失败',
                                'P2', 'OPEN', 3, :createdAt, :createdAt)
                        """).param("createdAt", createdAt).update();
    }

    private String createBody() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "recurrenceKey", RECURRENCE_KEY, "from", FROM, "to", TO));
    }

    private JsonNode data(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          String token) throws Exception {
        String response = mockMvc.perform(request.header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", "OpsPilot@2026"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("accessToken").asText();
    }

    private static String fingerprint(String source, long resourceId,
                                      String severity, String title) {
        String raw = source.trim().toLowerCase() + '|' + resourceId + '|'
                + severity.toUpperCase() + '|' + title.trim().toLowerCase().replaceAll("\\s+", " ");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
