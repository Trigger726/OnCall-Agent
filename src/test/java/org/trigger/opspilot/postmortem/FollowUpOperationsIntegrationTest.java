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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-follow-up-operations-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class FollowUpOperationsIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void shouldCreateOneEscalationAndResolveItWhenOwnerCompletesFollowUp() throws Exception {
        LocalDate asOf = LocalDate.now();
        jdbcClient.sql("""
                        INSERT INTO incident_postmortem(
                          id, incident_id, status, summary, customer_impact, root_cause,
                          contributing_factors, lessons_learned, timeline_snapshot_json,
                          evidence_refs_json, created_by, published_at)
                        VALUES (201, 2, 'PUBLISHED', '摘要', '影响', '根因', '因素', '经验',
                                '[]', '[]', 3, CURRENT_TIMESTAMP)
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO postmortem_follow_up(
                          id, postmortem_id, title, description, priority, status,
                          owner_id, due_date, created_by, completed_by, completed_at)
                        VALUES
                          (301, 201, '补齐兼容回归', '接入发布门禁', 'HIGH', 'OPEN',
                           2, :overdueDate, 3, NULL, NULL),
                          (302, 201, '检查截止当天边界', '当天不应逾期', 'MEDIUM', 'OPEN',
                           3, :today, 3, NULL, NULL),
                          (303, 201, '已完成旧任务', '完成项不应升级', 'LOW', 'DONE',
                           2, :oldDate, 3, 2, CURRENT_TIMESTAMP)
                        """).param("overdueDate", asOf.minusDays(2)).param("today", asOf)
                .param("oldDate", asOf.minusDays(5)).update();
        String manager = login("lina");
        String onCall = login("zhangwei");

        JsonNode firstScan = data(post("/api/v1/postmortem-follow-ups/escalations/run")
                .param("asOf", asOf.toString()), manager);
        assertThat(firstScan.path("overdueItems").asInt()).isEqualTo(1);
        assertThat(firstScan.path("createdEscalations").asInt()).isEqualTo(1);
        assertThat(firstScan.path("existingEscalations").asInt()).isZero();

        JsonNode repeatedScan = data(post("/api/v1/postmortem-follow-ups/escalations/run")
                .param("asOf", asOf.toString()), manager);
        assertThat(repeatedScan.path("createdEscalations").asInt()).isZero();
        assertThat(repeatedScan.path("existingEscalations").asInt()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM postmortem_follow_up_escalation")
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM incident_timeline
                        WHERE event_type = 'FOLLOW_UP_ESCALATED'
                        """).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM audit_log
                        WHERE action = 'POSTMORTEM_FOLLOW_UP_ESCALATED'
                        """).query(Long.class).single()).isEqualTo(1L);

        mockMvc.perform(get("/api/v1/postmortem-follow-ups")
                        .header("Authorization", bearer(manager))
                        .param("overdue", "true").param("asOf", asOf.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(301))
                .andExpect(jsonPath("$.data.items[0].overdue").value(true))
                .andExpect(jsonPath("$.data.items[0].daysOverdue").value(2))
                .andExpect(jsonPath("$.data.items[0].escalationStatus").value("OPEN"));
        mockMvc.perform(get("/api/v1/postmortem-follow-ups")
                        .header("Authorization", bearer(manager))
                        .param("scope", "MINE").param("asOf", asOf.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(302))
                .andExpect(jsonPath("$.data.items[0].overdue").value(false));
        mockMvc.perform(post("/api/v1/postmortem-follow-ups/escalations/run")
                        .header("Authorization", bearer(onCall))
                        .param("asOf", asOf.toString()))
                .andExpect(status().isForbidden());

        data(post("/api/v1/postmortem-follow-ups/301/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("expectedVersion", 0))), onCall);
        assertThat(jdbcClient.sql("""
                        SELECT status FROM postmortem_follow_up_escalation WHERE follow_up_id = 301
                        """).query(String.class).single()).isEqualTo("RESOLVED");
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM incident_timeline
                        WHERE event_type = 'FOLLOW_UP_ESCALATION_RESOLVED'
                        """).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM audit_log
                        WHERE action = 'POSTMORTEM_FOLLOW_UP_ESCALATION_RESOLVED'
                        """).query(Long.class).single()).isEqualTo(1L);
        mockMvc.perform(get("/api/v1/postmortem-follow-ups")
                        .header("Authorization", bearer(manager))
                        .param("overdue", "true").param("asOf", asOf.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
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

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
