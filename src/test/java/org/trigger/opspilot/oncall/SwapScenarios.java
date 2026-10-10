package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The same assertions run against H2 and a Spring-owned MySQL database. */
abstract class SwapScenarios {
    @Autowired private OnCallSwapService swaps;
    @Autowired private OnCallRosterService roster;
    @Autowired private OnCallCoverageService coverage;
    @Autowired private JdbcClient jdbc;
    @Autowired private DataSource datasource;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @SpyBean private AuditService audit;

    @BeforeEach
    void actualDatabaseIdentity(TestInfo test) throws Exception {
        try (var connection = datasource.getConnection()) {
            var metadata = connection.getMetaData();
            if (getClass().getSimpleName().startsWith("MySql")) {
                assertThat(metadata.getDatabaseProductName()).isEqualTo("MySQL");
                assertThat(metadata.getDatabaseProductVersion()).startsWith("8.4.");
            }
            assertThat(jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version='34' AND success=TRUE").query(Long.class).single()).isEqualTo(1);
            System.out.println("CP85_SWAP_DATABASE " + json.writeValueAsString(Map.of("case", test.getTestMethod().orElseThrow().getName(),
                    "product", metadata.getDatabaseProductName(), "version", metadata.getDatabaseProductVersion(), "schema", connection.getCatalog())));
        }
    }

    @Test void shouldExchangeBothFutureShiftsAtomicallyAndPreserveOrdinaryHistory() {
        var f = fixture(false); var command = command(f); var pending = swaps.request(command, 2, "test");
        long before = audits(); var accepted = swaps.decide(pending.id(), decision("ACCEPTED"), 3, "test");
        assertThat(accepted.status()).isEqualTo("ACCEPTED"); assertThat(accepted.version()).isEqualTo(1);
        assertThat(accepted.decidedBy()).isEqualTo(3L); assertThat(accepted.firstReplacementShiftId()).isNotEqualTo(accepted.secondReplacementShiftId());
        assertThat(swaps.decide(pending.id(), decision("ACCEPTED"), 3, "test")).isEqualTo(accepted);
        assertThat(swaps.request(command, 2, "test")).isEqualTo(accepted); assertThat(audits()).isEqualTo(before + 3);
        assertResponsibility(f.first(), 3); assertResponsibility(f.second(), 2); assertThat(shifts(f)).isEqualTo(4);
        for (var source : List.of(f.first(), f.second())) {
            assertThat(jdbc.sql("SELECT version FROM oncall_shift WHERE id=:id").param("id", source.id()).query(Integer.class).single()).isZero();
            assertThat(jdbc.sql("SELECT cancelled_at FROM oncall_shift WHERE id=:id").param("id", source.id()).query(LocalDateTime.class).single()).isNull();
        }
    }

    @Test void shouldExchangeAcrossSchedulesWithoutChangingEitherRotation() {
        var f = fixture(true); var pending = swaps.request(command(f), 2, "test");
        swaps.decide(pending.id(), decision("ACCEPTED"), 3, "test");
        assertResponsibility(f.first(), 3); assertResponsibility(f.second(), 2);
        assertThat(shifts(f)).isEqualTo(4); assertThat(swaps.list(f.second().scheduleId(), null, "ACCEPTED").requests()).extracting(OnCallSwapService.View::id).contains(pending.id());
    }

    @Test void shouldRequireActualParticipantsEvenForAdministrators() {
        var f = fixture(false);
        fails(() -> swaps.request(command(f), 1, "test"), "ONCALL_SWAP_FORBIDDEN");
        var pending = swaps.request(command(f), 2, "test");
        for (long actor : new long[]{1, 2, 4}) fails(() -> swaps.decide(pending.id(), decision("ACCEPTED"), actor, "test"), "ONCALL_SWAP_FORBIDDEN");
        fails(() -> swaps.decide(pending.id(), decision("WITHDRAWN"), 3, "test"), "ONCALL_SWAP_FORBIDDEN");
        assertThat(shifts(f)).isEqualTo(2);
    }

    @Test void shouldRecheckBothParticipantsAndPermitRejectionWhenRequesterIsDisabled() {
        var f = fixture(false); var pending = swaps.request(command(f), 2, "test");
        for (long participant : new long[]{2,3}) {
            try {
                jdbc.sql("UPDATE sys_user SET status='DISABLED' WHERE id=:id").param("id", participant).update();
                fails(() -> swaps.decide(pending.id(), decision("ACCEPTED"), 3, "test"), "ONCALL_SWAP_FORBIDDEN");
            } finally { jdbc.sql("UPDATE sys_user SET status='ACTIVE' WHERE id=:id").param("id", participant).update(); }
        }
        try {
            jdbc.sql("UPDATE sys_user SET status='DISABLED' WHERE id=2").update();
            assertThat(swaps.decide(pending.id(), decision("REJECTED"), 3, "test").status()).isEqualTo("REJECTED");
        } finally { jdbc.sql("UPDATE sys_user SET status='ACTIVE' WHERE id=2").update(); }
        assertThat(shifts(f)).isEqualTo(2);
    }

    @Test void shouldRejectEitherStaleSourceAndStillAllowWithdrawal() {
        for (boolean second : new boolean[]{false,true}) {
            var f = fixture(true); var pending = swaps.request(command(f), 2, "test"); var changed = second ? f.second() : f.first();
            roster.cancel(changed.id(), changed.version(), "原班次取消", 1L, "test"); long before = audits();
            fails(() -> swaps.decide(pending.id(), decision("ACCEPTED"), 3, "test"), "ONCALL_SWAP_SOURCE_CHANGED");
            assertThat(audits()).isEqualTo(before); assertThat(shifts(f)).isEqualTo(2);
            assertThat(swaps.decide(pending.id(), decision("WITHDRAWN"), 2, "test").status()).isEqualTo("WITHDRAWN");
        }
    }

    @Test void shouldRejectEitherInactiveScheduleAndStillAllowTargetRejection() {
        var f = fixture(true); var pending = swaps.request(command(f), 2, "test");
        jdbc.sql("UPDATE oncall_schedule SET active=FALSE WHERE id=:id").param("id", f.second().scheduleId()).update();
        fails(() -> swaps.decide(pending.id(), decision("ACCEPTED"), 3, "test"), "ONCALL_SCHEDULE_INACTIVE");
        assertThat(swaps.decide(pending.id(), decision("REJECTED"), 3, "test").status()).isEqualTo("REJECTED"); assertThat(shifts(f)).isEqualTo(2);
    }

    @Test void shouldRejectExistingOverridesAndLegacyOrdinaryOverlapWithoutHalfSwap() {
        for (boolean ordinary : new boolean[]{false,true}) {
            var f = fixture(true); var pending = swaps.request(command(f), 2, "test"); var s = f.second();
            if (ordinary) jdbc.sql("INSERT INTO oncall_shift(schedule_id,user_id,starts_at,ends_at) VALUES (:schedule,1,:start,:end)")
                    .param("schedule", s.scheduleId()).param("start", s.startsAt()).param("end", s.endsAt()).update();
            else roster.create(new OnCallRosterService.ShiftCommand(s.scheduleId(), 1, s.startsAt(), s.endsAt(), true, "冲突覆盖"), 1L, "test");
            long before = audits(); fails(() -> swaps.decide(pending.id(), decision("ACCEPTED"), 3, "test"), "ONCALL_SWAP_OVERLAP");
            assertThat(swaps.get(pending.id()).status()).isEqualTo("PENDING"); assertThat(shifts(f)).isEqualTo(3); assertThat(audits()).isEqualTo(before);
            assertResponsibility(f.first(), 2);
        }
    }

    @Test void shouldRejectSelfExchangeInvalidKeysAndChangedIdempotentPayload() {
        var f = fixture(false); var c = command(f); swaps.request(c, 2, "test");
        fails(() -> swaps.request(new OnCallSwapService.Command(c.firstShiftId(), 0, c.secondShiftId(), 0, c.requestKey(), "不同内容"), 2, "test"), "ONCALL_SWAP_KEY_REUSED");
        fails(() -> swaps.request(new OnCallSwapService.Command(c.firstShiftId(), 0, c.firstShiftId(), 0, UUID.randomUUID().toString(), "本人"), 2, "test"), "ONCALL_SWAP_INVALID");
        fails(() -> swaps.request(new OnCallSwapService.Command(c.firstShiftId(), 0, c.secondShiftId(), 0, "bad-key", "无效键"), 2, "test"), "ONCALL_SWAP_INVALID");
        fails(() -> swaps.request(new OnCallSwapService.Command(c.firstShiftId(), 1, c.secondShiftId(), 0, UUID.randomUUID().toString(), "旧版"), 2, "test"), "ONCALL_SWAP_SOURCE_CHANGED");
    }

    @Test void shouldRejectStartedShiftsAndOverrideSources() {
        var f = fixture(true);
        var ongoing = roster.create(new OnCallRosterService.ShiftCommand(f.first().scheduleId(), 2, now().minusHours(1).truncatedTo(ChronoUnit.SECONDS),
                now().plusHours(1).truncatedTo(ChronoUnit.SECONDS), false, "已开始"), 1L, "test");
        fails(() -> swaps.request(new OnCallSwapService.Command(ongoing.id(), 0, f.second().id(), 0, UUID.randomUUID().toString(), "已开始"), 2, "test"), "ONCALL_SWAP_STARTED");
        var cover = roster.create(new OnCallRosterService.ShiftCommand(f.first().scheduleId(), 2, f.first().startsAt(), f.first().endsAt(), true, "覆盖来源"), 1L, "test");
        fails(() -> swaps.request(new OnCallSwapService.Command(cover.id(), 0, f.second().id(), 0, UUID.randomUUID().toString(), "覆盖"), 2, "test"), "ONCALL_SWAP_SOURCE_CHANGED");
    }

    @Test void shouldRollbackBothOverridesWhenSecondCreationAuditFails() {
        var f = fixture(true); var pending = swaps.request(command(f), 2, "test"); long before = audits();
        doAnswer(call -> { throw new IllegalStateException("second-swap-audit-failure"); }).when(audit)
                .recordAs(eq(3L), eq("test"), eq("ONCALL_SHIFT_CREATED"), eq("ONCALL_SHIFT"), anyLong(), contains("第二段"));
        assertThatThrownBy(() -> swaps.decide(pending.id(), decision("ACCEPTED"), 3, "test")).hasMessage("second-swap-audit-failure");
        assertPendingWithoutOverrides(f, pending, before);
    }

    @Test void shouldRollbackBothOverridesDecisionAndAuditsWhenFinalAuditFails() {
        var f = fixture(true); var pending = swaps.request(command(f), 2, "test"); long before = audits();
        doAnswer(call -> { throw new IllegalStateException("final-swap-audit-failure"); }).when(audit)
                .recordAs(eq(3L), eq("test"), eq("ONCALL_SWAP_ACCEPTED"), eq("ONCALL_SWAP"), eq(pending.id()), anyString());
        assertThatThrownBy(() -> swaps.decide(pending.id(), decision("ACCEPTED"), 3, "test")).hasMessage("final-swap-audit-failure");
        assertPendingWithoutOverrides(f, pending, before);
    }

    @Test void shouldSerializeConcurrentSameKeyRequestsAndAcceptRetries() throws Exception {
        var f = fixture(true); var c = command(f);
        var requested = parallel(() -> swaps.request(c, 2, "test"), () -> swaps.request(c, 2, "test"));
        assertThat(requested.get(0)).isEqualTo(requested.get(1)); long before = audits();
        long id = requested.get(0).id(); var accepted = parallel(() -> swaps.decide(id, decision("ACCEPTED"), 3, "test"), () -> swaps.decide(id, decision("ACCEPTED"), 3, "test"));
        assertThat(accepted.get(0)).isEqualTo(accepted.get(1)); assertThat(shifts(f)).isEqualTo(4); assertThat(audits()).isEqualTo(before + 3);
    }

    @Test void shouldHaveOneWinnerBetweenAcceptanceAndWithdrawal() throws Exception {
        var f = fixture(true); long id = swaps.request(command(f), 2, "test").id();
        var results = parallel(() -> outcome(id, "ACCEPTED", 3), () -> outcome(id, "WITHDRAWN", 2));
        assertThat(results).contains("ONCALL_SWAP_VERSION_CONFLICT"); assertThat(swaps.get(id).version()).isEqualTo(1);
        assertThat(shifts(f)).isEqualTo(swaps.get(id).status().equals("ACCEPTED") ? 4 : 2);
    }

    @Test void shouldSerializeReverseCrossScheduleRequestsWithoutDeadlockOrDoubleExchange() throws Exception {
        var f = fixture(true); long first = swaps.request(command(f), 2, "test").id();
        long reverse = swaps.request(new OnCallSwapService.Command(f.second().id(), 0, f.first().id(), 0, UUID.randomUUID().toString(), "反向换班"), 3, "test").id();
        var results = parallel(() -> outcome(first, "ACCEPTED", 3), () -> outcome(reverse, "ACCEPTED", 2));
        assertThat(results).containsExactlyInAnyOrder("ACCEPTED", "ONCALL_SWAP_OVERLAP"); assertThat(shifts(f)).isEqualTo(4);
    }

    @Test void shouldAcknowledgeAcceptedHistoryWithoutRevivingCancelledCoverage() {
        var f = fixture(false); var c = command(f); var pending = swaps.request(c, 2, "test"); var accepted = swaps.decide(pending.id(), decision("ACCEPTED"), 3, "test");
        roster.cancel(accepted.firstReplacementShiftId(), 0, "管理撤销第一段覆盖", 1L, "test"); long before = audits();
        assertThat(swaps.decide(pending.id(), decision("ACCEPTED"), 3, "test")).isEqualTo(accepted);
        assertThat(swaps.request(c, 2, "test")).isEqualTo(accepted); assertThat(audits()).isEqualTo(before); assertThat(shifts(f)).isEqualTo(4);
        assertResponsibility(f.first(), 2); assertResponsibility(f.second(), 2); // Accepted is a historical agreement, not a claim both overrides remain active.
    }

    @Test void shouldFilterParticipantAndEitherScheduleBeforeLimitAndExposeTruncation() {
        var f = fixture(true); long wanted = swaps.request(command(f), 2, "test").id();
        for (int i=0;i<201;i++) jdbc.sql("""
                INSERT INTO oncall_shift_swap(requester_id,target_user_id,request_key,first_schedule_id,first_shift_id,first_version,
                  first_starts_at,first_ends_at,second_schedule_id,second_shift_id,second_version,second_starts_at,second_ends_at,reason)
                SELECT 1,4,:key,first_schedule_id,first_shift_id,first_version,first_starts_at,first_ends_at,
                  second_schedule_id,second_shift_id,second_version,second_starts_at,second_ends_at,'无关请求' FROM oncall_shift_swap WHERE id=:id
                """).param("key", UUID.randomUUID().toString()).param("id", wanted).update();
        assertThat(swaps.list(f.second().scheduleId(), 2L, "PENDING").requests()).extracting(OnCallSwapService.View::id).containsExactly(wanted);
        assertThat(swaps.list(f.second().scheduleId(), null, null).truncated()).isTrue();
    }

    @Test void shouldEnforceHttpRolesParticipantAndExplicitVersions() throws Exception {
        var f = fixture(false); String admin=login("admin"), owner=login("zhangwei"), target=login("lina"), auditor=login("auditor");
        String body=json.writeValueAsString(command(f));
        mvc.perform(post("/api/v1/on-call/swaps").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/on-call/swaps").header("Authorization",auditor).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        for (String field : List.of("firstVersion","secondVersion")) for(boolean missing:new boolean[]{false,true}) {
            var node=json.readTree(body).deepCopy(); if(missing)((com.fasterxml.jackson.databind.node.ObjectNode)node).remove(field); else ((com.fasterxml.jackson.databind.node.ObjectNode)node).putNull(field);
            mvc.perform(post("/api/v1/on-call/swaps").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(node.toString())).andExpect(status().isBadRequest());
        }
        String response=mvc.perform(post("/api/v1/on-call/swaps").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id=json.readTree(response).path("data").path("id").asLong(); String route="/api/v1/on-call/swaps/"+id+"/decisions";
        mvc.perform(post(route).header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(decision("ACCEPTED")))).andExpect(status().isForbidden());
        for(String bad:List.of("{\"status\":\"ACCEPTED\",\"reason\":\"接受\"}","{\"version\":null,\"status\":\"ACCEPTED\",\"reason\":\"接受\"}")) mvc.perform(post(route).header("Authorization",target).contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isBadRequest());
        mvc.perform(post(route).header("Authorization",target).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(decision("ACCEPTED")))).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        mvc.perform(get("/api/v1/on-call/swaps").header("Authorization",owner).param("scope","INVALID")).andExpect(status().isBadRequest());
    }

    private Fixture fixture(boolean differentSchedules) {
        long firstSchedule=schedule(),secondSchedule=differentSchedules?schedule():firstSchedule;
        var start=now().truncatedTo(ChronoUnit.SECONDS).plusDays(1);
        var first=roster.create(new OnCallRosterService.ShiftCommand(firstSchedule,2,start,start.plusHours(4),false,"本人班次"),1L,"test");
        var second=roster.create(new OnCallRosterService.ShiftCommand(secondSchedule,3,start.plusDays(1),start.plusDays(1).plusHours(4),false,"对方班次"),1L,"test");
        return new Fixture(first,second);
    }
    private long schedule() { long resource=insert(jdbc.sql("INSERT INTO cmdb_resource(resource_code,resource_type,name,environment,status) VALUES (:code,'APPLICATION','换班独立服务','TEST','RUNNING')").param("code","SWAP-"+UUID.randomUUID()));long schedule=insert(jdbc.sql("INSERT INTO oncall_schedule(service_resource_id,name) VALUES (:id,'换班计划')").param("id",resource));PlanMembershipFixtures.grant(jdbc,schedule,1,2,3);return schedule; }
    private OnCallSwapService.Command command(Fixture f) { return new OnCallSwapService.Command(f.first().id(),0,f.second().id(),0,UUID.randomUUID().toString(),"双方互换未来班次"); }
    private OnCallSwapService.Decision decision(String status) { return new OnCallSwapService.Decision(0,status,"双方决定"); }
    private String outcome(long id,String status,long actor) { try{return swaps.decide(id,decision(status),actor,"test").status();}catch(ApiException error){return error.code();} }
    private void assertResponsibility(OnCallRosterService.ShiftView source,long user) { assertThat(coverage.coverage(source.scheduleId(),source.startsAt(),source.endsAt()).segments()).allSatisfy(s->assertThat(s.userId()).isEqualTo(user)); }
    private void assertPendingWithoutOverrides(Fixture f,OnCallSwapService.View pending,long before) {var row=swaps.get(pending.id());assertThat(row.status()).isEqualTo("PENDING");assertThat(row.version()).isZero();assertThat(row.firstReplacementShiftId()).isNull();assertThat(row.secondReplacementShiftId()).isNull();assertThat(shifts(f)).isEqualTo(2);assertThat(audits()).isEqualTo(before);}
    private long shifts(Fixture f) {return jdbc.sql("SELECT COUNT(*) FROM oncall_shift WHERE schedule_id=:first OR schedule_id=:second").param("first",f.first().scheduleId()).param("second",f.second().scheduleId()).query(Long.class).single();}
    private long audits() {return jdbc.sql("SELECT COUNT(*) FROM audit_log").query(Long.class).single();}
    private LocalDateTime now() {return jdbc.sql("SELECT CURRENT_TIMESTAMP(6)").query((rs,n)->rs.getObject(1,LocalDateTime.class)).single();}
    private long insert(JdbcClient.StatementSpec statement) {var holder=new GeneratedKeyHolder();statement.update(holder,"id");return holder.getKey().longValue();}
    private void fails(Runnable action,String code) {assertThatThrownBy(action::run).isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);}
    private <T> List<T> parallel(Callable<T> first,Callable<T> second) throws Exception {var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);try{var a=pool.submit(()->{assertThat(gate.await(10,TimeUnit.SECONDS)).isTrue();return first.call();});var b=pool.submit(()->{assertThat(gate.await(10,TimeUnit.SECONDS)).isTrue();return second.call();});gate.countDown();return List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));}finally{gate.countDown();pool.shutdownNow();}}
    private String login(String username) throws Exception {String response=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\""+username+"\",\"password\":\"OpsPilot@2026\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();return "Bearer "+json.readTree(response).path("data").path("accessToken").asText();}
    private record Fixture(OnCallRosterService.ShiftView first,OnCallRosterService.ShiftView second) {}
}
