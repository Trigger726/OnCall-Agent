package org.trigger.opspilot.runbook;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-runbook-feedback-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class RunbookRelevanceFeedbackIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void shouldCaptureSearchEnforceIndependentReviewAndPromoteOnlyPositiveJudgments() throws Exception {
        String onCallToken = login("zhangwei");
        String adminToken = login("admin");
        String managerToken = login("lina");

        JsonNode onCallSearch = search(onCallToken, "消息积压 consumer group offset 位点");
        long searchId = onCallSearch.path("searchId").asLong();
        String stableKey = onCallSearch.path("results").path(0).path("stableKey").asText();
        String partiallyRelevantKey = onCallSearch.path("results").path(1).path("stableKey").asText();
        assertThat(searchId).isPositive();
        assertThat(stableKey).isEqualTo("kafka-consumer-lag");
        assertThat(jdbcClient.sql("SELECT source_type FROM runbook_retrieval_query WHERE id = :id")
                .param("id", searchId).query(String.class).single()).isEqualTo("CONSOLE");

        mockMvc.perform(post("/api/v1/runbooks/searches/{id}/judgments", searchId)
                        .header("Authorization", bearer(managerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("documentStableKey", stableKey, "relevanceGrade", 3))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("RUNBOOK_JUDGMENT_NOT_SEARCH_OWNER"));

        mockMvc.perform(post("/api/v1/runbooks/searches/{id}/judgments", searchId)
                        .header("Authorization", bearer(onCallToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("documentStableKey", "not-in-snapshot", "relevanceGrade", 3))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RUNBOOK_JUDGMENT_RESULT_MISMATCH"));

        mockMvc.perform(post("/api/v1/runbooks/searches/{id}/judgments", searchId)
                        .header("Authorization", bearer(onCallToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("documentStableKey", stableKey, "relevanceGrade", 4))))
                .andExpect(status().isBadRequest());

        String submitted = mockMvc.perform(post("/api/v1/runbooks/searches/{id}/judgments", searchId)
                        .header("Authorization", bearer(onCallToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("documentStableKey", stableKey, "relevanceGrade", 3,
                                "comment", "返回项直接覆盖本次 Kafka 积压处置"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.versionNo").value(0))
                .andReturn().getResponse().getContentAsString();
        long positiveJudgmentId = objectMapper.readTree(submitted).path("data").path("id").asLong();
        String partialSubmitted = mockMvc.perform(post("/api/v1/runbooks/searches/{id}/judgments", searchId)
                        .header("Authorization", bearer(onCallToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("documentStableKey", partiallyRelevantKey, "relevanceGrade", 2,
                                "comment", "可作为同一故障意图的补充处置"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long partialJudgmentId = objectMapper.readTree(partialSubmitted).path("data").path("id").asLong();
        JsonNode rejectedSearch = search(onCallToken, "磁盘 inode 使用率");
        long rejectedSearchId = rejectedSearch.path("searchId").asLong();
        String rejectedSampleKey = rejectedSearch.path("results").path(0).path("stableKey").asText();
        assertThat(rejectedSampleKey).isNotBlank();
        String rejectedSubmitted = mockMvc.perform(post("/api/v1/runbooks/searches/{id}/judgments", rejectedSearchId)
                        .header("Authorization", bearer(onCallToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("documentStableKey", rejectedSampleKey, "relevanceGrade", 1,
                                "comment", "该样本上下文不足，交由复核人决定是否采用"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long rejectedJudgmentId = objectMapper.readTree(rejectedSubmitted).path("data").path("id").asLong();

        mockMvc.perform(get("/api/v1/runbooks/judgments/pending")
                        .header("Authorization", bearer(onCallToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/runbooks/judgments/pending")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(positiveJudgmentId))
                .andExpect(jsonPath("$.data[0].query").value("消息积压 consumer group offset 位点"))
                .andExpect(jsonPath("$.data[0].documentTitle").isNotEmpty())
                .andExpect(jsonPath("$.data[0].documentExcerpt").isNotEmpty())
                .andExpect(jsonPath("$.data[0].citation").value("runbook:kafka-consumer-lag:v1#chunk-0"))
                .andExpect(jsonPath("$.data[0].relevanceGrade").doesNotExist())
                .andExpect(jsonPath("$.data[0].judgedByName").doesNotExist());

        mockMvc.perform(post("/api/v1/runbooks/judgments/{id}/reviews", positiveJudgmentId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", 0, "decision", "APPROVE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("RUNBOOK_REVIEWER_GRADE_REQUIRED"));

        mockMvc.perform(post("/api/v1/runbooks/judgments/{id}/reviews", positiveJudgmentId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", 0, "decision", "APPROVE", "reviewerGrade", 4))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/runbooks/judgments/{id}/reviews", positiveJudgmentId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", 0, "decision", "APPROVE",
                                "reviewerGrade", 3,
                                "note", "独立复核确认相关"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.reviewerGrade").value(3))
                .andExpect(jsonPath("$.data.promotedCaseKey").value("human-judgment-" + positiveJudgmentId));

        mockMvc.perform(post("/api/v1/runbooks/judgments/{id}/reviews", partialJudgmentId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", 0, "decision", "APPROVE",
                                "reviewerGrade", 2,
                                "note", "确认属于部分相关补充文档"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.promotedCaseKey").value("human-judgment-" + partialJudgmentId));

        mockMvc.perform(post("/api/v1/runbooks/judgments/{id}/reviews", rejectedJudgmentId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", 0, "decision", "REJECT",
                                "note", "快照不足以形成可信双评分"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.reviewerGrade").doesNotExist())
                .andExpect(jsonPath("$.data.promotedCaseKey").doesNotExist());

        mockMvc.perform(post("/api/v1/runbooks/judgments/{id}/reviews", positiveJudgmentId)
                        .header("Authorization", bearer(managerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", 0, "decision", "REJECT"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUNBOOK_JUDGMENT_VERSION_CONFLICT"));

        JsonNode adminSearch = search(adminToken, "Redis 连接池 pending 慢命令");
        long adminSearchId = adminSearch.path("searchId").asLong();
        String adminStableKey = adminSearch.path("results").path(0).path("stableKey").asText();
        String negativeSubmitted = mockMvc.perform(post("/api/v1/runbooks/searches/{id}/judgments", adminSearchId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("documentStableKey", adminStableKey, "relevanceGrade", 0,
                                "comment", "该返回项与实际意图不相关"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long negativeJudgmentId = objectMapper.readTree(negativeSubmitted).path("data").path("id").asLong();

        mockMvc.perform(post("/api/v1/runbooks/judgments/{id}/reviews", negativeJudgmentId)
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", 0, "decision", "APPROVE", "reviewerGrade", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RUNBOOK_JUDGMENT_SELF_REVIEW_FORBIDDEN"));

        mockMvc.perform(post("/api/v1/runbooks/judgments/{id}/reviews", negativeJudgmentId)
                        .header("Authorization", bearer(managerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", 0, "decision", "APPROVE", "reviewerGrade", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.reviewerGrade").value(1));

        mockMvc.perform(get("/api/v1/runbooks/judgments/agreement")
                        .header("Authorization", bearer(onCallToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/runbooks/judgments/agreement")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sampleCount").value(3))
                .andExpect(jsonPath("$.data.exactAgreementRate").value(0.666667))
                .andExpect(jsonPath("$.data.withinOneAgreementRate").value(1.0))
                .andExpect(jsonPath("$.data.linearWeightedKappa").value(0.727273));

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM runbook_retrieval_eval_case WHERE source_type = 'HUMAN_JUDGMENT'")
                .query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbcClient.sql("SELECT relevance_grade FROM runbook_retrieval_eval_case " +
                        "WHERE judgment_id = :id").param("id", partialJudgmentId)
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT reviewer_grade FROM runbook_relevance_judgment " +
                        "WHERE id = :id").param("id", negativeJudgmentId)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM runbook_retrieval_query")
                .query(Long.class).single()).isEqualTo(3L);

        mockMvc.perform(post("/api/v1/runbooks/evaluations")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.caseCount").value(14))
                .andExpect(jsonPath("$.data.judgmentCount").value(15))
                .andExpect(jsonPath("$.data.recallAt3").value(1.0))
                .andExpect(jsonPath("$.data.ndcgAt3").value(0.973638));
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM runbook_retrieval_query")
                .query(Long.class).single()).isEqualTo(3L);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM audit_log WHERE action IN " +
                        "('RUNBOOK_RELEVANCE_JUDGE','RUNBOOK_RELEVANCE_REVIEW')")
                .query(Long.class).single()).isEqualTo(8L);
    }

    private JsonNode search(String token, String query) throws Exception {
        String response = mockMvc.perform(get("/api/v1/runbooks/search")
                        .header("Authorization", bearer(token))
                        .queryParam("q", query)
                        .queryParam("mode", "BM25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.searchId").isNumber())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
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
