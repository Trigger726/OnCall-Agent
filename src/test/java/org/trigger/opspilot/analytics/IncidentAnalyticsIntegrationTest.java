package org.trigger.opspilot.analytics;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-analytics-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class IncidentAnalyticsIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void shouldExposeHonestMilestoneDenominatorsMedianAndDrilldown() throws Exception {
        mockMvc.perform(get("/analytics"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        jdbcClient.sql("""
                        INSERT INTO incident(
                          id, incident_code, title, severity, status, service_resource_id,
                          acknowledged_at, resolved_at, created_at, updated_at)
                        VALUES
                          (101, 'INC-ANALYTICS-101', '十分钟确认六十分钟恢复', 'P2', 'RESOLVED', 3,
                           '2026-08-20 10:10:00', '2026-08-20 11:00:00',
                           '2026-08-20 10:00:00', '2026-08-20 11:00:00'),
                          (102, 'INC-ANALYTICS-102', '缺失全部响应里程碑', 'P2', 'OPEN', 3,
                           NULL, NULL, '2026-08-20 12:00:00', '2026-08-20 12:00:00'),
                          (103, 'INC-ANALYTICS-103', '异常负时长数据', 'P2', 'RESOLVED', 3,
                           '2026-08-20 13:00:00', '2026-08-20 13:30:00',
                           '2026-08-20 14:00:00', '2026-08-20 14:00:00')
                        """).update();
        jdbcClient.sql("""
                        INSERT INTO incident_timeline(
                          incident_id, event_type, from_status, to_status, content, created_at)
                        VALUES
                          (101, 'STATUS_CHANGED', 'INVESTIGATING', 'MITIGATED', '恢复主要服务',
                           '2026-08-20 10:30:00'),
                          (103, 'STATUS_CHANGED', 'INVESTIGATING', 'MITIGATED', '错误的历史时间',
                           '2026-08-20 13:40:00')
                        """).update();
        String token = login("lina");

        String response = mockMvc.perform(get("/api/v1/analytics/incidents")
                        .header("Authorization", bearer(token))
                        .param("from", "2026-08-01").param("to", "2026-08-31")
                        .param("severity", "p2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incidentCount").value(4))
                .andExpect(jsonPath("$.data.mtta.sampleCount").value(2))
                .andExpect(jsonPath("$.data.mtta.averageMinutes").value(6.5))
                .andExpect(jsonPath("$.data.mtta.medianMinutes").value(6.5))
                .andExpect(jsonPath("$.data.mttm.sampleCount").value(2))
                .andExpect(jsonPath("$.data.mttm.averageMinutes").value(27.5))
                .andExpect(jsonPath("$.data.mttr.sampleCount").value(2))
                .andExpect(jsonPath("$.data.mttr.averageMinutes").value(50.5))
                .andExpect(jsonPath("$.data.slowestResolved.length()").value(2))
                .andExpect(jsonPath("$.data.slowestResolved[0].incidentCode")
                        .value("INC-ANALYTICS-101"))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(response).path("data");
        assertThat(data.path("severityDistribution").get(0).path("severity").asText())
                .isEqualTo("P2");
        assertThat(data.path("severityDistribution").get(0).path("count").asLong())
                .isEqualTo(4);
        assertThat(data.path("slowestResolved").toString())
                .doesNotContain("INC-ANALYTICS-103");

        mockMvc.perform(get("/api/v1/analytics/incidents")
                        .header("Authorization", bearer(token))
                        .param("from", "2026-09-01").param("to", "2026-08-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ANALYTICS_INVALID_WINDOW"));
        mockMvc.perform(get("/api/v1/analytics/incidents")
                        .header("Authorization", bearer(token))
                        .param("severity", "P0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ANALYTICS_INVALID_SEVERITY"));
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
