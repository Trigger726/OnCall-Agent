package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.trigger.opspilot.alert.AlertService;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-incident-escalation-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false"
})
@AutoConfigureMockMvc
class IncidentEscalationIntegrationTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    @Autowired private JdbcClient jdbcClient;
    @Autowired private IncidentEscalationService service;
    @Autowired private OnCallService onCallService;
    @Autowired private AlertService alertService;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void shouldRouteOverrideThenStopAfterAcknowledgement() {
        LocalDateTime createdAt = LocalDateTime.now(BUSINESS_ZONE)
                .minusMinutes(1).truncatedTo(ChronoUnit.SECONDS);
        long incidentId = incident(createdAt);
        jdbcClient.sql("""
                        INSERT INTO oncall_shift(schedule_id, user_id, starts_at, ends_at, override_flag)
                        VALUES (1, 2, :start, :end, FALSE), (1, 3, :start, :end, TRUE)
                        """).param("start", createdAt.minusMinutes(1))
                .param("end", createdAt.plusMinutes(30)).update();
        assertThat(onCallService.current().stream()
                .filter(shift -> shift.scheduleId() == 1).toList())
                .singleElement().extracting(OnCallService.OnCallView::userName)
                .isEqualTo("李娜");

        var first = service.scan(createdAt, null, "test");
        assertThat(first.routedSteps()).isEqualTo(1);
        assertThat(first.noTargetSteps()).isZero();
        assertThat(recipient(incidentId, 1)).isEqualTo("lina");
        assertThat(service.scan(createdAt, null, "test").routedSteps()).isZero();

        assertThat(service.scan(createdAt.plusMinutes(10), null, "test").routedSteps()).isEqualTo(1);
        assertThat(recipient(incidentId, 2)).isEqualTo("lina");
        jdbcClient.sql("UPDATE incident SET status = 'ACKNOWLEDGED' WHERE id = :id")
                .param("id", incidentId).update();
        assertThat(service.scan(createdAt.plusMinutes(20), null, "test").routedSteps()).isZero();
        assertThat(eventCount(incidentId)).isEqualTo(2);
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM notification_log WHERE incident_id = :id AND status = 'RECORDED'")
                .param("id", incidentId).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void shouldRecordMissingShiftWithoutClaimingDeliveryAndThenRouteLaterSteps() {
        LocalDateTime createdAt = LocalDateTime.now(BUSINESS_ZONE)
                .plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        long incidentId = incident(createdAt);
        var first = service.scan(createdAt, null, "test");
        assertThat(first.noTargetSteps()).isEqualTo(1);
        assertThat(first.routedSteps()).isZero();
        assertThat(jdbcClient.sql("""
                        SELECT status FROM incident_escalation_event
                        WHERE incident_id = :id
                        """).param("id", incidentId).query(String.class).single()).isEqualTo("NO_TARGET");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM notification_log WHERE incident_id = :id")
                .param("id", incidentId).query(Long.class).single()).isZero();

        var later = service.scan(createdAt.plusMinutes(20), null, "test");
        assertThat(later.routedSteps()).isEqualTo(2);
        assertThat(recipient(incidentId, 2)).isEqualTo("lina");
        assertThat(recipient(incidentId, 3)).isEqualTo("role:ADMIN");
        assertThat(eventCount(incidentId)).isEqualTo(3);
        jdbcClient.sql("UPDATE incident SET status = 'ACKNOWLEDGED' WHERE id = :id")
                .param("id", incidentId).update();
    }

    @Test
    void shouldExecuteEachStepOnceAcrossConcurrentScans() throws Exception {
        LocalDateTime createdAt = LocalDateTime.now(BUSINESS_ZONE)
                .minusMinutes(1).truncatedTo(ChronoUnit.SECONDS);
        long incidentId = incident(createdAt);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return service.scan(createdAt, null, "test");
            });
            var second = executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return service.scan(createdAt, null, "test");
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertThat(eventCount(incidentId)).isEqualTo(1);
            assertThat(service.scan(createdAt, null, "test").routedSteps()).isZero();
        } finally {
            start.countDown();
            executor.shutdownNow();
            jdbcClient.sql("UPDATE incident SET status = 'ACKNOWLEDGED' WHERE id = :id")
                    .param("id", incidentId).update();
        }
    }

    @Test
    void shouldRouteFirstStepAsPartOfNewAlertTransaction() {
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE).truncatedTo(ChronoUnit.SECONDS);
        jdbcClient.sql("""
                        INSERT INTO oncall_shift(schedule_id, user_id, starts_at, ends_at)
                        VALUES (1, 2, :start, :end)
                        """).param("start", now.minusMinutes(1)).param("end", now.plusMinutes(30)).update();
        var intake = alertService.intake(new AlertService.IntakeRequest(
                "escalation-test", UUID.randomUUID().toString(), "APP-SETTLEMENT", "P1",
                "FIRING", "新告警即时值班路由 " + UUID.randomUUID(), "验收", Map.of(), now));
        long incidentId = intake.incidentId();
        assertThat(eventCount(incidentId)).isEqualTo(1);
        assertThat(jdbcClient.sql("""
                        SELECT status FROM incident_escalation_event WHERE incident_id = :id
                        """).param("id", incidentId).query(String.class).single()).isEqualTo("ROUTED");
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM incident_timeline
                        WHERE incident_id = :id AND event_type = 'ESCALATION_ROUTED'
                        """).param("id", incidentId).query(Long.class).single()).isEqualTo(1);
        jdbcClient.sql("UPDATE incident SET status = 'ACKNOWLEDGED' WHERE id = :id")
                .param("id", incidentId).update();
    }

    @Test
    void shouldExposeHistoryButRestrictManualScan() throws Exception {
        String auditor = login("auditor");
        String admin = login("admin");
        mockMvc.perform(get("/api/v1/on-call/escalations").header("Authorization", bearer(auditor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        mockMvc.perform(post("/api/v1/on-call/escalations/scan")
                        .header("Authorization", bearer(auditor)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/on-call/escalations/scan")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.routedSteps").isNumber());
    }

    private long incident(LocalDateTime createdAt) {
        String code = "ESC-" + UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO incident(incident_code, title, severity, status, service_resource_id,
                                             created_at, updated_at)
                        VALUES (:code, '升级策略验收', 'P1', 'OPEN', 1, :createdAt, :createdAt)
                        """).param("code", code).param("createdAt", createdAt).update();
        return jdbcClient.sql("SELECT id FROM incident WHERE incident_code = :code")
                .param("code", code).query(Long.class).single();
    }

    private long eventCount(long incidentId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM incident_escalation_event WHERE incident_id = :id")
                .param("id", incidentId).query(Long.class).single();
    }

    private String recipient(long incidentId, int step) {
        return jdbcClient.sql("""
                        SELECT event.recipient FROM incident_escalation_event event
                        JOIN escalation_step s ON s.id = event.step_id
                        WHERE event.incident_id = :id AND s.step_order = :step
                        """).param("id", incidentId).param("step", step).query(String.class).single();
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
