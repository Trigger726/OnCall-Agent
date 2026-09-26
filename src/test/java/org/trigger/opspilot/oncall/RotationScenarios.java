package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.trigger.opspilot.common.ApiException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class RotationScenarios {
    @Autowired protected JdbcClient jdbc;
    @Autowired protected OnCallRotationService rotations;
    @SpyBean protected OnCallRosterService roster;
    @Autowired private OnCallService onCall;
    @Autowired private IncidentEscalationService escalation;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @Test
    void shouldGenerateStableRoundRobinAndRouteTheActualFirstOwner() {
        long schedule = schedule();
        LocalDateTime at = now().minusMinutes(1);
        var rotation = rotations.create(command(schedule, at, 480, List.of(2L, 3L)), 1L, "test");
        var slots = rotations.slots(rotation.id(), null, null).slots();
        assertThat(slots).hasSizeGreaterThan(40);
        for (int index = 0; index < slots.size(); index++) {
            assertThat(slots.get(index).slot()).isEqualTo(index);
            assertThat(slots.get(index).userId()).isEqualTo(index % 2 == 0 ? 2L : 3L);
            assertThat(slots.get(index).startsAt()).isEqualTo(at.plusMinutes(index * 480L));
            assertThat(slots.get(index).endsAt()).isEqualTo(at.plusMinutes((index + 1) * 480L));
            assertThat(slots.get(index).status()).isEqualTo("GENERATED");
        }
        assertThat(rotations.scan(null, null, "test").createdShifts()).isZero();
        assertThat(onCall.current().stream().filter(view -> view.scheduleId() == schedule).findFirst().orElseThrow().userId())
                .isEqualTo(2L);
        long policy = insert(jdbc.sql("INSERT INTO escalation_policy(service_resource_id, name, severity) VALUES (3, :name, 'P1')")
                .param("name", "轮转路由"));
        jdbc.sql("INSERT INTO escalation_step(policy_id, step_order, delay_minutes, target_type, target_ref) VALUES (:id, 1, 0, 'ON_CALL', :ref)")
                .param("id", policy).param("ref", "schedule:" + schedule).update();
        long incident = insert(jdbc.sql("INSERT INTO incident(incident_code, title, severity, status, service_resource_id) VALUES (:code, '轮转路由', 'P1', 'OPEN', 3)")
                .param("code", UUID.randomUUID().toString()));
        escalation.routeNewIncident(incident);
        assertThat(jdbc.sql("SELECT recipient FROM incident_escalation_event WHERE incident_id = :id AND policy_id = :policy")
                .param("id", incident).param("policy", policy).query(String.class).single()).isEqualTo("zhangwei");
        jdbc.sql("UPDATE incident SET status = 'ACKNOWLEDGED' WHERE id = :id").param("id", incident).update();
        jdbc.sql("UPDATE escalation_policy SET active = FALSE WHERE id = :id").param("id", policy).update();
    }

    @Test
    void shouldRespectManualConflictAndRetryWithoutRevivingCancelledGeneratedShift() {
        long schedule = schedule();
        LocalDateTime at = now().minusMinutes(1);
        var manual = roster.create(new OnCallRosterService.ShiftCommand(schedule, 3, at, at.plusHours(8), false, "手工班"), 1L, "test");
        var rotation = rotations.create(command(schedule, at, 480, List.of(2L)), 1L, "test");
        assertThat(rotations.slots(rotation.id(), null, null).slots().get(0).status()).isEqualTo("BLOCKED");
        roster.cancel(manual.id(), 0, "释放手工占用", 1L, "test");
        assertThat(rotations.scan(null, null, "test").createdShifts()).isEqualTo(1);
        var first = rotations.slots(rotation.id(), null, null).slots().get(0);
        assertThat(first.status()).isEqualTo("GENERATED");
        roster.cancel(first.shiftId(), 0, "有意留空", 1L, "test");
        assertThat(rotations.scan(null, null, "test").createdShifts()).isZero();
        assertThat(rotations.slots(rotation.id(), null, null).slots().get(0).cancelledAt()).isNotNull();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM oncall_shift WHERE rotation_id = :id AND rotation_slot = 0")
                .param("id", rotation.id()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void shouldKeepOverridePriorityAndPauseOnlyFutureGeneration() {
        long schedule = schedule();
        LocalDateTime at = now().minusMinutes(1);
        var rotation = rotations.create(command(schedule, at, 1440, List.of(2L)), 1L, "test");
        var cover = roster.create(new OnCallRosterService.ShiftCommand(schedule, 3, at, at.plusHours(1), true, "临时覆盖"), 1L, "test");
        assertThat(onCall.current().stream().filter(view -> view.scheduleId() == schedule).findFirst().orElseThrow().userId()).isEqualTo(3L);
        rotations.state(rotation.id(), 0, false, "暂停续排，不撤销班次", 1L, "test");
        long count = shiftCount(rotation.id());
        rotations.scan(now().plusDays(14), null, "test");
        assertThat(shiftCount(rotation.id())).isEqualTo(count);
        assertThatThrownBy(() -> rotations.state(rotation.id(), 0, true, "旧页面", 1L, "test"))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_ROTATION_VERSION_CONFLICT");
        rotations.state(rotation.id(), 1, true, "恢复续排", 1L, "test");
        assertThat(rotations.scan(now().plusDays(14), null, "test").createdShifts()).isPositive();
        assertThat(shiftCount(rotation.id())).isGreaterThan(count);
        assertThat(roster.roster(schedule, null, null).shifts().stream().filter(shift -> shift.id() == cover.id()).findFirst().orElseThrow().cancelledAt()).isNull();
    }

    @Test
    void shouldRecordUnavailableMemberWithoutSilentlyChangingTheMemberOrder() {
        String username = "rotation-" + UUID.randomUUID();
        long member = insert(jdbc.sql("INSERT INTO sys_user(username, password_hash, display_name, role_code) SELECT :name, password_hash, '轮转用户', 'ON_CALL' FROM sys_user WHERE id = 2")
                .param("name", username));
        long schedule = schedule();
        LocalDateTime at = now().plusDays(20);
        var rotation = rotations.create(command(schedule, at, 1440, List.of(member, 2L)), 1L, "test");
        jdbc.sql("UPDATE sys_user SET status = 'DISABLED' WHERE id = :id").param("id", member).update();
        rotations.scan(now().plusDays(20), null, "test");
        var slots = rotations.slots(rotation.id(), at, at.plusDays(14)).slots();
        assertThat(slots.get(0).status()).isEqualTo("MEMBER_UNAVAILABLE");
        assertThat(slots.get(0).shiftId()).isNull();
        assertThat(slots.get(1).userId()).isEqualTo(2L);
        assertThat(slots.get(1).status()).isEqualTo("GENERATED");
        jdbc.sql("UPDATE sys_user SET status = 'ACTIVE' WHERE id = :id").param("id", member).update();
        rotations.scan(now().plusDays(20), null, "test");
        assertThat(rotations.slots(rotation.id(), at, at.plusDays(14)).slots().get(0).status()).isEqualTo("GENERATED");
    }

    @Test
    void shouldSerializeConcurrentScansAndConcurrentRotationCreation() throws Exception {
        long schedule = schedule();
        LocalDateTime at = now().minusMinutes(1);
        var barrier = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> create = () -> {
                if (!barrier.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
                try { rotations.create(command(schedule, at, 1440, List.of(2L, 3L)), 1L, "test"); return "CREATED"; }
                catch (ApiException error) { return error.code(); }
            };
            var first = executor.submit(create);
            var second = executor.submit(create);
            barrier.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("CREATED", "ONCALL_ROTATION_EXISTS");
            long id = rotations.list(schedule).rotations().get(0).id();
            var one = executor.submit(() -> rotations.scan(now().plusDays(14), null, "test"));
            var two = executor.submit(() -> rotations.scan(now().plusDays(14), null, "test"));
            assertThat(one.get(20, TimeUnit.SECONDS).failedRotations()).isEmpty();
            assertThat(two.get(20, TimeUnit.SECONDS).failedRotations()).isEmpty();
            assertThat(jdbc.sql("SELECT COUNT(*) - COUNT(DISTINCT rotation_slot) FROM oncall_shift WHERE rotation_id = :id")
                    .param("id", id).query(Long.class).single()).isZero();
        } finally { barrier.countDown(); executor.shutdownNow(); }
    }

    @Test
    void shouldRejectInvalidRulesAndKeepHistoricalDemoUntouched() {
        long schedule = schedule();
        LocalDateTime at = now();
        var historic = jdbc.sql("SELECT id, starts_at, ends_at, note FROM oncall_shift WHERE id IN (1,2) ORDER BY id").query().listOfRows();
        for (var command : List.of(command(schedule, at, 59, List.of(2L)), command(schedule, at, 10081, List.of(2L)),
                command(schedule, at.plusSeconds(1), 60, List.of(2L)), command(schedule, at.plusDays(32), 60, List.of(2L)),
                command(schedule, at, 60, List.of(2L, 2L)), command(schedule, at, 60, List.of(4L)))) {
            assertThatThrownBy(() -> rotations.create(command, 1L, "test")).isInstanceOf(ApiException.class)
                    .extracting("code").isEqualTo("ONCALL_ROTATION_INVALID");
        }
        assertThatThrownBy(() -> rotations.create(null, 1L, "test")).isInstanceOf(ApiException.class);
        rotations.create(command(schedule, at.minusMinutes(1), 1440, List.of(2L)), 1L, "test");
        assertThat(jdbc.sql("SELECT id, starts_at, ends_at, note FROM oncall_shift WHERE id IN (1,2) ORDER BY id").query().listOfRows()).isEqualTo(historic);
    }

    @Test
    void shouldExposeBoundedSlotWindowAndStopWhenScheduleIsDisabled() {
        long schedule = schedule();
        LocalDateTime at = now().minusMinutes(1);
        var rotation = rotations.create(command(schedule, at, 60, List.of(2L)), 1L, "test");
        assertThat(rotations.slots(rotation.id(), at, at.plusDays(14)).slots()).hasSize(200);
        assertThat(rotations.slots(rotation.id(), at, at.plusDays(14)).truncated()).isTrue();
        assertThat(rotations.slots(rotation.id(), at.plusHours(1), at.plusHours(2)).slots()).singleElement()
                .extracting(OnCallRotationService.SlotView::slot).isEqualTo(1L);
        long count = shiftCount(rotation.id());
        jdbc.sql("UPDATE oncall_schedule SET active = FALSE WHERE id = :id").param("id", schedule).update();
        rotations.scan(now().plusDays(14), null, "test");
        assertThat(shiftCount(rotation.id())).isEqualTo(count);
        assertThat(rotations.get(rotation.id()).lastWarning()).isEqualTo("SCHEDULE_INACTIVE");
    }

    @Test
    void shouldRequireManagementRolesForWritesAndAuditTheHttpActor() throws Exception {
        long schedule = schedule();
        var command = command(schedule, now().minusMinutes(1), 1440, List.of(2L, 3L));
        String auditor = login("auditor"), admin = login("admin"), operator = login("zhangwei");
        for (String token : List.of(auditor, operator)) mvc.perform(post("/api/v1/on-call/rotations")
                .header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(command)))
                .andExpect(status().isForbidden());
        String response = mvc.perform(post("/api/v1/on-call/rotations").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(command)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(response).path("data").path("id").asLong();
        mvc.perform(get("/api/v1/on-call/rotations/{id}/slots", id).header("Authorization", auditor)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/on-call/rotations/{id}/state", id).header("Authorization", auditor)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"active\":false,\"reason\":\"暂停\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/on-call/rotations/scan").header("Authorization", auditor)).andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT actor_id FROM audit_log WHERE action = 'ONCALL_ROTATION_CREATED' AND target_id = :id")
                .param("id", Long.toString(id)).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void shouldRollBackOneFailedRotationAndContinueOtherRotations() {
        long badSchedule = schedule(), goodSchedule = schedule();
        LocalDateTime at = now().plusDays(20);
        var bad = rotations.create(command(badSchedule, at, 1440, List.of(2L)), 1L, "test");
        var good = rotations.create(command(goodSchedule, at, 1440, List.of(2L)), 1L, "test");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            OnCallRosterService.ShiftCommand command = call.getArgument(0);
            if (command.scheduleId() == badSchedule && calls.incrementAndGet() == 2) {
                throw new IllegalStateException("rotation-failure-sentinel");
            }
            return call.callRealMethod();
        }).when(roster).create(any(OnCallRosterService.ShiftCommand.class), any(), anyString());
        var result = rotations.scan(now().plusDays(20), null, "test");
        assertThat(result.failedRotations()).containsExactly(bad.id());
        assertThat(shiftCount(bad.id())).isZero();
        assertThat(rotations.slots(bad.id(), at, at.plusDays(14)).slots()).isEmpty();
        assertThat(rotations.get(bad.id()).lastWarning()).isEqualTo("GENERATION_FAILED");
        assertThat(shiftCount(good.id())).isPositive();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'ONCALL_SHIFT_CREATED' AND detail LIKE :note")
                .param("note", "%轮转#" + bad.id() + " 时段#%").query(Long.class).single()).isZero();
    }

    @Test
    void shouldReportMemberRevocationAfterGenerationWithoutReassigningHistoricalFacts() {
        long user = insert(jdbc.sql("INSERT INTO sys_user(username, password_hash, display_name, role_code) SELECT :name, password_hash, '资格变更验收', 'ON_CALL' FROM sys_user WHERE id = 2")
                .param("name", "revoked-" + UUID.randomUUID()));
        long schedule = schedule();
        var rotation = rotations.create(command(schedule, now().minusMinutes(1), 1440, List.of(user)), 1L, "test");
        var first = rotations.slots(rotation.id(), null, null).slots().get(0);
        jdbc.sql("UPDATE sys_user SET role_code = 'AUDITOR' WHERE id = :id").param("id", user).update();
        assertThat(rotations.scan(null, null, "test").blockedSlots()).isPositive();
        var reread = rotations.slots(rotation.id(), null, null).slots().get(0);
        assertThat(reread.status()).isEqualTo("GENERATED");
        assertThat(reread.shiftId()).isEqualTo(first.shiftId());
        assertThat(reread.memberAvailable()).isFalse();
        assertThat(rotations.get(rotation.id()).lastWarning()).startsWith("BLOCKED_SLOTS:");
        assertThat(onCall.current().stream().filter(view -> view.scheduleId() == schedule).findFirst().orElseThrow().userId()).isNull();
        long policy = insert(jdbc.sql("INSERT INTO escalation_policy(service_resource_id, name, severity) VALUES (3, '资格撤销策略', 'P1')"));
        jdbc.sql("INSERT INTO escalation_step(policy_id, step_order, delay_minutes, target_type, target_ref) VALUES (:id, 1, 0, 'ON_CALL', :ref)")
                .param("id", policy).param("ref", "schedule:" + schedule).update();
        long incident = insert(jdbc.sql("INSERT INTO incident(incident_code, title, severity, status, service_resource_id) VALUES (:code, '资格撤销路由', 'P1', 'OPEN', 3)")
                .param("code", UUID.randomUUID().toString()));
        escalation.routeNewIncident(incident);
        assertThat(jdbc.sql("SELECT status FROM incident_escalation_event WHERE incident_id = :id AND policy_id = :policy")
                .param("id", incident).param("policy", policy).query(String.class).single()).isEqualTo("NO_TARGET");
        jdbc.sql("UPDATE incident SET status = 'ACKNOWLEDGED' WHERE id = :id").param("id", incident).update();
        jdbc.sql("UPDATE escalation_policy SET active = FALSE WHERE id = :id").param("id", policy).update();
    }

    protected long schedule() { return insert(jdbc.sql("INSERT INTO oncall_schedule(service_resource_id, name) VALUES (3, :name)")
            .param("name", "轮转-" + UUID.randomUUID())); }
    protected LocalDateTime now() { return jdbc.sql("SELECT CURRENT_TIMESTAMP")
            .query((rs, row) -> rs.getObject(1, LocalDateTime.class)).single().truncatedTo(ChronoUnit.MINUTES); }
    protected OnCallRotationService.Command command(long schedule, LocalDateTime anchor, int minutes, List<Long> members) {
        return new OnCallRotationService.Command(schedule, "轮转验收", anchor, minutes, members);
    }
    private long shiftCount(long id) { return jdbc.sql("SELECT COUNT(*) FROM oncall_shift WHERE rotation_id = :id")
            .param("id", id).query(Long.class).single(); }
    protected static long insert(JdbcClient.StatementSpec statement) {
        var key = new GeneratedKeyHolder(); statement.update(key, "id"); return key.getKey().longValue();
    }
    private String login(String name) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", name, "password", "OpsPilot@2026"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(response).path("data").path("accessToken").asText();
    }
}
