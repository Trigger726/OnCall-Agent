package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.trigger.opspilot.common.ApiException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-roster-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "opspilot.ai.enabled=false"
})
@AutoConfigureMockMvc
class OnCallRosterIntegrationTest {
    @Autowired private JdbcClient jdbc;
    @Autowired private OnCallRosterService roster;
    @Autowired private OnCallService onCall;
    @Autowired private IncidentEscalationService escalation;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void shouldEnforceRolesAndKeepAnAuditedCancellationHistory() throws Exception {
        long schedule = schedule();
        LocalDateTime at = now();
        var command = command(schedule, 2, at.minusMinutes(1), at.plusHours(8), false);
        for (String username : new String[]{"auditor", "zhangwei"}) {
            mvc.perform(post("/api/v1/on-call/shifts").header("Authorization", login(username))
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(command)))
                    .andExpect(status().isForbidden());
        }
        String admin = login("admin");
        String response = mvc.perform(post("/api/v1/on-call/shifts").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(command)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(0))
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(response).path("data").path("id").asLong();
        mvc.perform(get("/api/v1/on-call/roster").param("scheduleId", Long.toString(schedule))
                        .header("Authorization", login("auditor")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.shifts[0].id").value(id));
        mvc.perform(post("/api/v1/on-call/shifts/{id}/cancel", id).header("Authorization", login("auditor"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"不可越权\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/on-call/shifts/{id}/cancel", id).header("Authorization", login("lina"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"reason\":\"换班已协调\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.cancellationReason").value("换班已协调"));
        assertThat(roster.roster(schedule, null, null).shifts()).singleElement()
                .satisfies(shift -> assertThat(shift.cancelledAt()).isNotNull());
        assertThat(onCall.current().stream().filter(item -> item.scheduleId() == schedule).findFirst().orElseThrow().userId())
                .isNull();
        assertThat(jdbc.sql("SELECT actor_id FROM audit_log WHERE target_id = :id AND action = 'ONCALL_SHIFT_CREATED'")
                .param("id", Long.toString(id)).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT actor_id FROM audit_log WHERE target_id = :id AND action = 'ONCALL_SHIFT_CANCELLED'")
                .param("id", Long.toString(id)).query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void shouldResumeBaseRoutingAfterAnOverrideIsCancelled() {
        long schedule = schedule();
        LocalDateTime at = now();
        var base = roster.create(command(schedule, 2, at.minusMinutes(1), at.plusHours(4), false), 1L, "test");
        var cover = roster.create(command(schedule, 3, at.minusMinutes(1), at.plusHours(1), true), 1L, "test");
        long policy = insert("INSERT INTO escalation_policy(service_resource_id, name, severity) VALUES (3, '排班验收', 'P1')");
        jdbc.sql("""
                INSERT INTO escalation_step(policy_id, step_order, delay_minutes, target_type, target_ref)
                VALUES (:policy, 1, 0, 'ON_CALL', :target)
                """).param("policy", policy).param("target", "schedule:" + schedule).update();
        assertThat(currentUser(schedule)).isEqualTo(3);
        assertThat(routeNewIncident()).isEqualTo("lina");
        roster.cancel(cover.id(), 0, "覆盖结束", 1L, "test");
        assertThat(currentUser(schedule)).isEqualTo(2);
        assertThat(routeNewIncident()).isEqualTo("zhangwei");
        roster.cancel(base.id(), 0, "本班撤销", 1L, "test");
        assertThat(currentUser(schedule)).isNull();
        assertThat(routeNewIncident()).isEqualTo("NO_TARGET");
    }

    @Test
    void shouldRejectSameLayerOverlapButAllowAdjacentHandoffsAndReplacement() {
        long schedule = schedule();
        LocalDateTime at = now().plusDays(1);
        var first = roster.create(command(schedule, 2, at, at.plusHours(8), false), 1L, "test");
        assertThatThrownBy(() -> roster.create(command(schedule, 3, at.plusHours(1), at.plusHours(9), false), 1L, "test"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_SHIFT_OVERLAP");
        roster.create(command(schedule, 3, at.plusHours(8), at.plusHours(16), false), 1L, "test");
        roster.create(command(schedule, 3, at.plusHours(1), at.plusHours(2), true), 1L, "test");
        assertThatThrownBy(() -> roster.create(command(schedule, 2, at.plusHours(1), at.plusHours(3), true), 1L, "test"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_SHIFT_OVERLAP");
        roster.cancel(first.id(), 0, "由新班次替代", 1L, "test");
        roster.create(command(schedule, 3, at, at.plusHours(8), false), 1L, "test");
        assertThat(roster.roster(schedule, null, null).shifts()).hasSize(4);
    }

    @Test
    void shouldRejectInvalidWindowsUsersAndStaleCancellationWithoutExtraAudit() throws Exception {
        long schedule = schedule();
        LocalDateTime at = now();
        for (var command : new OnCallRosterService.ShiftCommand[]{
                command(schedule, 2, at, at, false), command(schedule, 2, at.minusDays(2), at.minusDays(1), false),
                command(schedule, 2, at, at.plusDays(32), false), command(schedule, 4, at, at.plusHours(1), false),
                command(schedule, 9999, at, at.plusHours(1), false),
                command(schedule, 2, at.plusNanos(1), at.plusHours(1), false)}) {
            assertThatThrownBy(() -> roster.create(command, 1L, "test")).isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo("ONCALL_ROSTER_INVALID");
        }
        var shift = roster.create(command(schedule, 2, at, at.plusHours(1), false), 1L, "test");
        assertThatThrownBy(() -> roster.cancel(shift.id(), 5, "旧页面", 1L, "test"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_SHIFT_VERSION_CONFLICT");
        roster.cancel(shift.id(), 0, "正确版本", 1L, "test");
        assertThatThrownBy(() -> roster.cancel(shift.id(), 0, "重复取消", 1L, "test"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_SHIFT_VERSION_CONFLICT");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'ONCALL_SHIFT_CANCELLED' AND target_id = :id")
                .param("id", Long.toString(shift.id())).query(Long.class).single()).isEqualTo(1);
        String admin = login("admin");
        mvc.perform(get("/api/v1/on-call/roster").param("from", "invalid").header("Authorization", admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/on-call/roster").param("from", at.toString()).param("to", at.plusDays(32).toString())
                        .header("Authorization", admin)).andExpect(status().isBadRequest());
        jdbc.sql("UPDATE oncall_schedule SET active = FALSE WHERE id = :id").param("id", schedule).update();
        assertThatThrownBy(() -> roster.create(command(schedule, 2, at, at.plusHours(1), false), 1L, "test"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_SCHEDULE_INACTIVE");
    }

    @Test
    void shouldSerializeConcurrentOverlappingCreates() throws Exception {
        long schedule = schedule();
        LocalDateTime at = now().plusDays(1);
        var command = command(schedule, 2, at, at.plusHours(8), false);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> create = () -> {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out");
                try { roster.create(command, 1L, "test"); return "CREATED"; }
                catch (ApiException error) { return error.code(); }
            };
            var first = executor.submit(create);
            var second = executor.submit(create);
            start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CREATED", "ONCALL_SHIFT_OVERLAP");
            assertThat(roster.roster(schedule, null, null).shifts()).hasSize(1);
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test
    void shouldUseHalfOpenQueryWindowsAndMakeTruncationExplicit() {
        long schedule = schedule();
        LocalDateTime at = now().plusDays(1);
        for (int index = 0; index < 205; index++) {
            jdbc.sql("""
                    INSERT INTO oncall_shift(schedule_id, user_id, starts_at, ends_at)
                    VALUES (:schedule, 2, :start, :end)
                    """).param("schedule", schedule).param("start", at.plusMinutes(index * 30L))
                    .param("end", at.plusMinutes((index + 1) * 30L)).update();
        }
        var small = roster.roster(schedule, at, at.plusMinutes(30));
        assertThat(small.shifts()).hasSize(1);
        assertThat(small.truncated()).isFalse();
        var large = roster.roster(schedule, null, null);
        assertThat(large.shifts()).hasSize(200);
        assertThat(large.truncated()).isTrue();
    }

    private String routeNewIncident() {
        long id = insert("INSERT INTO incident(incident_code, title, severity, status, service_resource_id) VALUES ('"
                + UUID.randomUUID() + "', '路由验收', 'P1', 'OPEN', 3)");
        escalation.routeNewIncident(id);
        String result = jdbc.sql("SELECT COALESCE(recipient, status) FROM incident_escalation_event WHERE incident_id = :id")
                .param("id", id).query(String.class).single();
        jdbc.sql("UPDATE incident SET status = 'ACKNOWLEDGED' WHERE id = :id").param("id", id).update();
        return result;
    }

    private Long currentUser(long schedule) {
        return onCall.current().stream().filter(item -> item.scheduleId() == schedule).findFirst().orElseThrow().userId();
    }

    private long schedule() {
        return insert("INSERT INTO oncall_schedule(service_resource_id, name) VALUES (3, '排班-" + UUID.randomUUID() + "')");
    }

    private long insert(String sql) {
        var key = new GeneratedKeyHolder();
        jdbc.sql(sql).update(key, "id");
        return key.getKey().longValue();
    }

    private LocalDateTime now() {
        return jdbc.sql("SELECT CURRENT_TIMESTAMP").query((rs, row) -> rs.getObject(1, LocalDateTime.class))
                .single().truncatedTo(ChronoUnit.SECONDS);
    }

    private OnCallRosterService.ShiftCommand command(long schedule, long user, LocalDateTime start, LocalDateTime end, boolean cover) {
        return new OnCallRosterService.ShiftCommand(schedule, user, start, end, cover, "排班已协调");
    }

    private String login(String username) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", "OpsPilot@2026"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(response).path("data").path("accessToken").asText();
    }
}
