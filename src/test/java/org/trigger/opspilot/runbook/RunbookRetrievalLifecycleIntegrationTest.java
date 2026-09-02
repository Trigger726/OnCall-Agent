package org.trigger.opspilot.runbook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-runbook-lifecycle-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false",
        "opspilot.runbook.retrieval.lifecycle.retention=P1D"
})
@AutoConfigureMockMvc
@Transactional
class RunbookRetrievalLifecycleIntegrationTest {
    @Autowired
    private RunbookRetrievalFeedbackService feedbackService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldRedactPurgeAuditAndPreservePromotedQrel() throws Exception {
        String onCallToken = login("zhangwei");
        String managerToken = login("lina");
        String rawQuery = "snapshot password=topsecret owner=admin@example.com host=10.20.30.40";
        List<RunbookService.SearchResult> results = sensitiveResults();
        long approvedSearchId = feedbackService.recordSearch(rawQuery, "CONSOLE", "BM25",
                "BM25_LOCAL_V1", "ON_CALL", "NOT_REQUESTED", BigDecimal.ZERO,
                1, 5, 12, results, 2L);
        long pendingSearchId = feedbackService.recordSearch(rawQuery + " pending", "CONSOLE", "BM25",
                "BM25_LOCAL_V1", "ON_CALL", "NOT_REQUESTED", BigDecimal.ZERO,
                1, 5, 13, results, 2L);

        Map<String, Object> persisted = jdbcClient.sql("""
                        SELECT query_text, results_json, redacted_fields
                        FROM runbook_retrieval_query WHERE id = :id
                        """).param("id", approvedSearchId).query().singleRow();
        String safeQuery = persisted.get("query_text").toString();
        String safeResults = persisted.get("results_json").toString();
        assertThat(safeQuery)
                .contains("password=***", "a***@example.com", "10.20.30.*")
                .doesNotContain("topsecret", "admin@example.com", "10.20.30.40");
        assertThat(safeResults)
                .contains("password=***", "a***@example.com", "10.20.30.*", "authorization=Bearer ***")
                .doesNotContain("supersecret", "admin@example.com", "10.20.30.40", "live-token");
        assertThat(((Number) persisted.get("redacted_fields")).intValue()).isGreaterThanOrEqualTo(3);

        long approvedJudgmentId = submitJudgment(onCallToken, approvedSearchId,
                "sensitive-runbook", 3, "password=review-secret admin@example.com");
        mockMvc.perform(post("/api/v1/runbooks/judgments/{id}/reviews", approvedJudgmentId)
                        .header("Authorization", bearer(managerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", 0, "decision", "APPROVE",
                                "reviewerGrade", 3, "note", "owner=admin@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.promotedCaseKey").value("human-judgment-" + approvedJudgmentId));
        long pendingJudgmentId = submitJudgment(onCallToken, pendingSearchId,
                "sensitive-runbook", 2, "password=pending-secret");

        LocalDateTime expiredAt = LocalDateTime.now().minusDays(2);
        jdbcClient.sql("UPDATE runbook_retrieval_query SET created_at = :createdAt WHERE id IN (:ids)")
                .param("createdAt", expiredAt).param("ids", List.of(approvedSearchId, pendingSearchId)).update();

        mockMvc.perform(get("/api/v1/runbooks/searches/retention")
                        .header("Authorization", bearer(onCallToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/runbooks/searches/retention")
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retention").value("PT24H"))
                .andExpect(jsonPath("$.data.activeSnapshots").value(2))
                .andExpect(jsonPath("$.data.expiredSnapshots").value(2));
        mockMvc.perform(post("/api/v1/runbooks/searches/retention/purge")
                        .header("Authorization", bearer(onCallToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/runbooks/searches/retention/purge")
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.purgedSnapshots").value(2))
                .andExpect(jsonPath("$.data.expiredPendingJudgments").value(1))
                .andExpect(jsonPath("$.data.preservedEvaluationCases").value(1));

        List<Map<String, Object>> purged = jdbcClient.sql("""
                        SELECT query_text, results_json, query_hash, created_by, snapshot_status, purged_at
                        FROM runbook_retrieval_query WHERE id IN (:ids) ORDER BY id
                        """).param("ids", List.of(approvedSearchId, pendingSearchId)).query().listOfRows();
        assertThat(purged).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("query_text")).isEqualTo("[PURGED]");
            assertThat(row.get("results_json")).isEqualTo("[]");
            assertThat(row.get("created_by")).isNull();
            assertThat(row.get("snapshot_status")).isEqualTo("PURGED");
            assertThat(row.get("purged_at")).isNotNull();
        });
        assertThat(purged.get(0).get("query_hash")).isEqualTo(purged.get(1).get("query_hash"));

        Map<String, Object> expiredJudgment = jdbcClient.sql("""
                        SELECT review_status, reviewer_grade, comment, review_note
                        FROM runbook_relevance_judgment WHERE id = :id
                        """).param("id", pendingJudgmentId).query().singleRow();
        assertThat(expiredJudgment.get("review_status")).isEqualTo("REJECTED");
        assertThat(expiredJudgment.get("reviewer_grade")).isNull();
        assertThat(expiredJudgment.get("comment")).isNull();
        assertThat(expiredJudgment.get("review_note")).isEqualTo("AUTO_EXPIRED_BY_RETENTION");

        Map<String, Object> qrel = jdbcClient.sql("""
                        SELECT query_text, expected_stable_key, relevance_grade, enabled
                        FROM runbook_retrieval_eval_case WHERE judgment_id = :id
                        """).param("id", approvedJudgmentId).query().singleRow();
        assertThat(qrel.get("query_text")).isEqualTo(safeQuery);
        assertThat(qrel.get("expected_stable_key")).isEqualTo("sensitive-runbook");
        assertThat(((Number) qrel.get("relevance_grade")).intValue()).isEqualTo(3);

        mockMvc.perform(post("/api/v1/runbooks/searches/{id}/judgments", approvedSearchId)
                        .header("Authorization", bearer(onCallToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("documentStableKey", "sensitive-runbook", "relevanceGrade", 3))))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("RUNBOOK_SEARCH_SNAPSHOT_PURGED"));
        mockMvc.perform(post("/api/v1/runbooks/searches/retention/purge")
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.purgedSnapshots").value(0));
        mockMvc.perform(get("/api/v1/runbooks/searches/retention")
                        .header("Authorization", bearer(managerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeSnapshots").value(0))
                .andExpect(jsonPath("$.data.expiredSnapshots").value(0))
                .andExpect(jsonPath("$.data.purgedSnapshots").value(2));
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'RUNBOOK_RETRIEVAL_PURGE'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    private List<RunbookService.SearchResult> sensitiveResults() {
        return List.of(new RunbookService.SearchResult(99L, "sensitive-runbook", 1,
                "APPLICATION", "10.20.30.40", "Contact admin@example.com", "MARKDOWN",
                0, "Credential recovery", "password=supersecret authorization=Bearer live-token host 10.20.30.40",
                BigDecimal.ONE, 1, null, null,
                "runbook:sensitive-runbook:v1#chunk-0", LocalDateTime.now()));
    }

    private long submitJudgment(String token, long searchId, String stableKey, int grade, String comment)
            throws Exception {
        String response = mockMvc.perform(post("/api/v1/runbooks/searches/{id}/judgments", searchId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("documentStableKey", stableKey,
                                "relevanceGrade", grade, "comment", comment))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("id").asLong();
    }

    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"OpsPilot@2026\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("accessToken").asText();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
