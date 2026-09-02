package org.trigger.opspilot.postmortem;

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

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-postmortem-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class PostmortemIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void shouldCreateReviewPublishAndCompleteEvidenceBackedPostmortem() throws Exception {
        String manager = login("lina");
        String admin = login("admin");
        String onCall = login("zhangwei");
        String auditor = login("auditor");

        jdbcClient.sql("""
                        INSERT INTO incident_timeline(incident_id, event_type, actor_id, content, evidence_ref)
                        VALUES (2, 'NOTE', 3,
                                '兼容测试账号 admin@example.com，password=timeline-secret',
                                'manual:compatibility-note?token=evidence-secret')
                        """).update();

        mockMvc.perform(post("/api/v1/incidents/1/postmortem")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POSTMORTEM_INCIDENT_NOT_RESOLVED"));

        JsonNode draft = data(post("/api/v1/incidents/2/postmortem"), manager);
        long postmortemId = draft.path("id").asLong();
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("version").asInt()).isZero();
        assertThat(draft.path("timelineSnapshot")).hasSize(4);
        assertThat(draft.path("timelineSnapshot").toString()).contains("a***@example.com", "password=***")
                .contains("token=***").doesNotContain("admin@example.com", "timeline-secret", "evidence-secret");
        assertThat(draft.path("evidenceRefs").toString()).contains("alert:3", "change:2");
        assertThat(draft.path("rootCause").asText()).contains("【待补充】");

        JsonNode reused = data(post("/api/v1/incidents/2/postmortem"), manager);
        assertThat(reused.path("id").asLong()).isEqualTo(postmortemId);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM incident_postmortem WHERE incident_id = 2")
                .query(Long.class).single()).isEqualTo(1L);

        mockMvc.perform(post("/api/v1/postmortems/{id}/submit", postmortemId)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", 0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POSTMORTEM_INCOMPLETE"));

        JsonNode updated = data(patch("/api/v1/postmortems/{id}", postmortemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "expectedVersion", 0,
                        "summary", "认证发布后旧客户端刷新失败，联系 admin@example.com",
                        "customerImpact", "部分旧版客户端无法续期会话，未发生数据丢失。",
                        "rootCause", "authorization=Bearer live-token 的旧格式兼容分支缺少回归覆盖。",
                        "contributingFactors", "灰度维度未按客户端版本拆分，告警只能看到总错误率。",
                        "lessonsLearned", "发布前需要按客户端版本执行兼容性验证，并保留回滚开关。"
                ))), manager);
        assertThat(updated.path("version").asInt()).isEqualTo(1);
        assertThat(updated.path("summary").asText()).contains("a***@example.com").doesNotContain("admin@example.com");
        assertThat(updated.path("rootCause").asText()).contains("authorization=Bearer ***")
                .doesNotContain("live-token");

        mockMvc.perform(post("/api/v1/postmortems/{id}/submit", postmortemId)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POSTMORTEM_FOLLOW_UP_REQUIRED"));

        LocalDate dueDate = LocalDate.now().plusDays(7);
        mockMvc.perform(post("/api/v1/postmortems/{id}/follow-ups", postmortemId)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "expectedPostmortemVersion", 1, "expectedVersion", 0,
                                "title", "分配给只读审计员", "description", "该行动项无法由负责人闭环",
                                "priority", "HIGH", "ownerId", 4, "dueDate", dueDate.toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("POSTMORTEM_FOLLOW_UP_OWNER_NOT_ELIGIBLE"));
        JsonNode withFollowUp = data(post("/api/v1/postmortems/{id}/follow-ups", postmortemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(followUp(1, 0, "补充旧客户端兼容回归", dueDate))), manager);
        long followUpId = withFollowUp.path("followUps").get(0).path("id").asLong();
        assertThat(withFollowUp.path("version").asInt()).isEqualTo(2);
        assertThat(withFollowUp.path("followUps").get(0).path("ownerName").asText()).isEqualTo("张伟");

        mockMvc.perform(post("/api/v1/postmortems/{id}/follow-ups", postmortemId)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(followUp(1, 0, "并发重复行动项", dueDate))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POSTMORTEM_VERSION_CONFLICT"));
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM postmortem_follow_up WHERE postmortem_id = :id")
                .param("id", postmortemId).query(Long.class).single()).isEqualTo(1L);

        jdbcClient.sql("UPDATE incident SET status = 'INVESTIGATING' WHERE id = 2").update();
        mockMvc.perform(post("/api/v1/postmortems/{id}/submit", postmortemId)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", 2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POSTMORTEM_INCIDENT_NOT_RESOLVED"));
        jdbcClient.sql("UPDATE incident SET status = 'RESOLVED' WHERE id = 2").update();

        JsonNode submitted = data(post("/api/v1/postmortems/{id}/submit", postmortemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("expectedVersion", 2))), manager);
        assertThat(submitted.path("status").asText()).isEqualTo("IN_REVIEW");
        assertThat(submitted.path("submittedByName").asText()).isEqualTo("李娜");
        assertThat(submitted.path("version").asInt()).isEqualTo(3);

        mockMvc.perform(patch("/api/v1/postmortem-follow-ups/{id}", followUpId)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(followUp(3, 0, "复核中不允许修改", dueDate))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POSTMORTEM_NOT_EDITABLE"));
        mockMvc.perform(post("/api/v1/postmortems/{id}/reviews", postmortemId)
                        .header("Authorization", bearer(onCall))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(review(3, "PUBLISH", "同意发布")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/postmortems/{id}/reviews", postmortemId)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(review(3, "PUBLISH", "同意发布")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("POSTMORTEM_SELF_REVIEW_FORBIDDEN"));

        JsonNode changesRequested = data(post("/api/v1/postmortems/{id}/reviews", postmortemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(review(3, "REQUEST_CHANGES", "需要量化兼容性测试范围")), admin);
        assertThat(changesRequested.path("status").asText()).isEqualTo("DRAFT");
        assertThat(changesRequested.path("version").asInt()).isEqualTo(4);

        JsonNode revised = data(patch("/api/v1/postmortems/{id}", postmortemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                        "expectedVersion", 4,
                        "summary", changesRequested.path("summary").asText(),
                        "customerImpact", changesRequested.path("customerImpact").asText(),
                        "rootCause", changesRequested.path("rootCause").asText(),
                        "contributingFactors", changesRequested.path("contributingFactors").asText(),
                        "lessonsLearned", "覆盖最近三个客户端大版本的 token 刷新契约，并在灰度阶段按版本观察。"
                ))), manager);
        assertThat(revised.path("version").asInt()).isEqualTo(5);

        JsonNode followUpUpdated = data(patch("/api/v1/postmortem-follow-ups/{id}", followUpId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(followUp(5, 0, "覆盖三个客户端版本的兼容回归", dueDate.plusDays(1)))), manager);
        assertThat(followUpUpdated.path("version").asInt()).isEqualTo(6);
        assertThat(followUpUpdated.path("followUps").get(0).path("version").asInt()).isEqualTo(1);

        JsonNode resubmitted = data(post("/api/v1/postmortems/{id}/submit", postmortemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("expectedVersion", 6))), manager);
        assertThat(resubmitted.path("version").asInt()).isEqualTo(7);

        jdbcClient.sql("UPDATE incident SET status = 'INVESTIGATING' WHERE id = 2").update();
        mockMvc.perform(post("/api/v1/postmortems/{id}/reviews", postmortemId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(review(7, "PUBLISH", "事故重开期间不能发布")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POSTMORTEM_INCIDENT_NOT_RESOLVED"));
        jdbcClient.sql("UPDATE incident SET status = 'RESOLVED' WHERE id = 2").update();

        JsonNode published = data(post("/api/v1/postmortems/{id}/reviews", postmortemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(review(7, "PUBLISH", "证据和行动项完整，同意发布")), admin);
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.path("reviewedByName").asText()).isEqualTo("系统管理员");
        assertThat(published.path("publishedAt").isTextual()).isTrue();
        assertThat(published.path("version").asInt()).isEqualTo(8);
        assertThat(published.path("timelineSnapshot")).hasSize(4);

        mockMvc.perform(patch("/api/v1/postmortems/{id}", postmortemId)
                        .header("Authorization", bearer(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "expectedVersion", 8, "summary", "发布后篡改",
                                "customerImpact", "不可修改", "rootCause", "不可修改",
                                "contributingFactors", "不可修改", "lessonsLearned", "不可修改"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POSTMORTEM_NOT_EDITABLE"));
        mockMvc.perform(post("/api/v1/postmortem-follow-ups/{id}/complete", followUpId)
                        .header("Authorization", bearer(auditor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", 1))))
                .andExpect(status().isForbidden());

        JsonNode completed = data(post("/api/v1/postmortem-follow-ups/{id}/complete", followUpId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("expectedVersion", 1))), onCall);
        assertThat(completed.path("followUps").get(0).path("status").asText()).isEqualTo("DONE");
        assertThat(completed.path("followUps").get(0).path("completedByName").asText()).isEqualTo("张伟");
        mockMvc.perform(post("/api/v1/postmortem-follow-ups/{id}/complete", followUpId)
                        .header("Authorization", bearer(onCall))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("POSTMORTEM_FOLLOW_UP_VERSION_CONFLICT"));

        mockMvc.perform(get("/api/v1/incidents/2/postmortem")
                        .header("Authorization", bearer(auditor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.timelineSnapshot.length()").value(4));
        assertThat(jdbcClient.sql("""
                        SELECT summary || ' ' || root_cause FROM incident_postmortem WHERE id = :id
                        """).param("id", postmortemId).query(String.class).single())
                .doesNotContain("admin@example.com", "live-token");
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM incident_timeline
                        WHERE incident_id = 2 AND event_type LIKE 'POSTMORTEM%'
                        """).query(Long.class).single()).isEqualTo(5L);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM audit_log
                        WHERE target_type IN ('INCIDENT_POSTMORTEM', 'POSTMORTEM_FOLLOW_UP')
                        """).query(Long.class).single()).isEqualTo(10L);
    }

    private Map<String, Object> followUp(int postmortemVersion, int followUpVersion,
                                         String title, LocalDate dueDate) {
        return Map.of("expectedPostmortemVersion", postmortemVersion,
                "expectedVersion", followUpVersion, "title", title,
                "description", "增加契约测试并接入发布门禁，验证旧 token 刷新格式。",
                "priority", "HIGH", "ownerId", 2, "dueDate", dueDate.toString());
    }

    private String review(int version, String decision, String comment) throws Exception {
        return json(Map.of("expectedVersion", version, "decision", decision, "comment", comment));
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
                        .content(json(Map.of("username", username, "password", "OpsPilot@2026"))))
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
