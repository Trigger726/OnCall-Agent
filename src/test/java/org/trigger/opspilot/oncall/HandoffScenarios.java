package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.trigger.opspilot.alert.AlertService;
import org.trigger.opspilot.audit.AuditService;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Identical business assertions execute on H2 MODE=MySQL and real MySQL 8.4.
abstract class HandoffScenarios {
    @Autowired private OnCallHandoffService handoffs;
    @Autowired private OnCallRosterService roster;
    @Autowired private OnCallCoverageService coverage;
    @Autowired private OnCallService current;
    @Autowired private AlertService alerts;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private PlatformTransactionManager transactions;
    @SpyBean private JdbcClient jdbc;
    @SpyBean private AuditService audit;

    @Test
    void shouldAcceptExactlyOnceAndPreserveSourceAndCoverageHistory() {
        var f = fixture(false);
        var command = command(f);
        var pending = handoffs.request(command, 2, "test");
        long audits = audits();
        var accepted = handoffs.decide(pending.id(), decision("ACCEPTED"), 3, "test");
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(accepted.version()).isEqualTo(1);
        assertThat(accepted.decidedBy()).isEqualTo(3L);
        assertThat(handoffs.decide(pending.id(), decision("ACCEPTED"), 3, "test")).isEqualTo(accepted);
        assertThat(handoffs.request(command, 2, "test")).isEqualTo(accepted);
        assertThat(audits()).isEqualTo(audits + 2); // one override creation plus one decision
        assertThat(shifts(f)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT version FROM oncall_shift WHERE id=:id").param("id", f.source().id()).query(Integer.class).single()).isZero();
        assertThat(coverage.coverage(f.schedule(), command.startsAt(), command.endsAt()).segments())
                .allSatisfy(s -> assertThat(s.userId()).isEqualTo(3L));
        var cover = replacement(accepted);
        roster.cancel(cover.id(), cover.version(), "已批准覆盖取消", 1L, "test");
        assertThat(handoffs.decide(pending.id(), decision("ACCEPTED"), 3, "test").replacementShiftId()).isEqualTo(cover.id());
        assertThat(shifts(f)).isEqualTo(2); // a retry must not revive the cancelled override
        assertThat(coverage.coverage(f.schedule(), command.startsAt(), command.endsAt()).segments())
                .allSatisfy(s -> assertThat(s.userId()).isEqualTo(2L));
        fails(() -> handoffs.decide(pending.id(), new OnCallHandoffService.Decision(0,"ACCEPTED","不同说明"),3,"test"), "ONCALL_HANDOFF_VERSION_CONFLICT");
    }

    @Test
    void shouldEnforceParticipantIdentityEvenForAdminsAndFreshRoles() {
        var f = fixture(false);
        fails(() -> handoffs.request(command(f),1,"test"), "ONCALL_HANDOFF_FORBIDDEN");
        var pending = handoffs.request(command(f),2,"test");
        for (long actor : new long[]{1,2,4}) {
            fails(() -> handoffs.decide(pending.id(),decision("ACCEPTED"),actor,"test"), "ONCALL_HANDOFF_FORBIDDEN");
        }
        fails(() -> handoffs.decide(pending.id(),decision("WITHDRAWN"),3,"test"), "ONCALL_HANDOFF_FORBIDDEN");
        try {
            jdbc.sql("UPDATE sys_user SET role_code='AUDITOR' WHERE id=3").update();
            fails(() -> handoffs.decide(pending.id(),decision("ACCEPTED"),3,"test"), "ONCALL_HANDOFF_FORBIDDEN");
        } finally { jdbc.sql("UPDATE sys_user SET role_code='OPS_MANAGER' WHERE id=3").update(); }
        assertThat(shifts(f)).isEqualTo(1);
    }

    @Test
    void shouldRejectSourceCancellationAndStillAllowWithdrawal() {
        var f = fixture(false);
        var pending = handoffs.request(command(f),2,"test");
        roster.cancel(f.source().id(),f.source().version(),"原班次取消",1L,"test");
        long audits = audits();
        fails(() -> handoffs.decide(pending.id(),decision("ACCEPTED"),3,"test"), "ONCALL_HANDOFF_SOURCE_CHANGED");
        assertThat(audits()).isEqualTo(audits);
        assertThat(shifts(f)).isEqualTo(1);
        assertThat(handoffs.decide(pending.id(),decision("WITHDRAWN"),2,"test").status()).isEqualTo("WITHDRAWN");
    }

    @Test
    void shouldRejectInactivePlanOrRequesterAndAllowTargetRejection() {
        var f = fixture(false);
        var pending = handoffs.request(command(f),2,"test");
        try {
            jdbc.sql("UPDATE sys_user SET status='DISABLED' WHERE id=2").update();
            fails(() -> handoffs.decide(pending.id(),decision("ACCEPTED"),3,"test"), "ONCALL_HANDOFF_FORBIDDEN");
        } finally { jdbc.sql("UPDATE sys_user SET status='ACTIVE' WHERE id=2").update(); }
        jdbc.sql("UPDATE oncall_schedule SET active=FALSE WHERE id=:id").param("id", f.schedule()).update();
        fails(() -> handoffs.decide(pending.id(),decision("ACCEPTED"),3,"test"), "ONCALL_SCHEDULE_INACTIVE");
        fails(() -> handoffs.request(command(f),2,"test"), "ONCALL_SCHEDULE_INACTIVE");
        assertThat(handoffs.decide(pending.id(),decision("REJECTED"),3,"test").status()).isEqualTo("REJECTED");
        long audits = audits();
        handoffs.decide(pending.id(),decision("REJECTED"),3,"test");
        assertThat(audits()).isEqualTo(audits);
    }

    @Test
    void shouldRejectNewOverrideAndLegacyOrdinaryOverlapWithoutPartialWrites() {
        var f = fixture(false);
        var pending = handoffs.request(command(f),2,"test");
        var cover = roster.create(new OnCallRosterService.ShiftCommand(f.schedule(),1,f.source().startsAt(),f.source().endsAt(),true,"已存在覆盖"),1L,"test");
        long audits = audits();
        fails(() -> handoffs.decide(pending.id(),decision("ACCEPTED"),3,"test"), "ONCALL_HANDOFF_OVERLAP");
        assertThat(audits()).isEqualTo(audits);
        roster.cancel(cover.id(),cover.version(),"移除冲突",1L,"test");
        jdbc.sql("INSERT INTO oncall_shift(schedule_id,user_id,starts_at,ends_at) VALUES (:id,1,:start,:end)")
                .param("id",f.schedule()).param("start",f.source().startsAt()).param("end",f.source().endsAt()).update();
        fails(() -> handoffs.request(command(f),2,"test"), "ONCALL_HANDOFF_OVERLAP");
        fails(() -> handoffs.decide(pending.id(),decision("ACCEPTED"),3,"test"), "ONCALL_HANDOFF_OVERLAP");
        assertThat(handoffs.list(f.schedule()).requests().get(0).status()).isEqualTo("PENDING");
    }

    @Test
    void shouldRejectChangedPayloadForSameKeyAndInvalidWindows() {
        var f = fixture(false);
        var c = command(f);
        handoffs.request(c,2,"test");
        fails(() -> handoffs.request(new OnCallHandoffService.Command(c.sourceShiftId(),c.sourceVersion(),1,c.requestKey(),c.startsAt(),c.endsAt(),c.reason()),2,"test"), "ONCALL_HANDOFF_KEY_REUSED");
        fails(() -> handoffs.request(new OnCallHandoffService.Command(c.sourceShiftId(),c.sourceVersion(),3,c.requestKey(),c.startsAt(),c.endsAt(),"更改内容"),2,"test"), "ONCALL_HANDOFF_KEY_REUSED");
        for (var bad : List.of(
                new OnCallHandoffService.Command(c.sourceShiftId(),0,2,UUID.randomUUID().toString(),c.startsAt(),c.endsAt(),"本人"),
                new OnCallHandoffService.Command(c.sourceShiftId(),0,3,"bad-key",c.startsAt(),c.endsAt(),"键"),
                new OnCallHandoffService.Command(c.sourceShiftId(),0,3,UUID.randomUUID().toString(),c.startsAt().minusSeconds(1),c.endsAt(),"越界"),
                new OnCallHandoffService.Command(c.sourceShiftId(),0,3,UUID.randomUUID().toString(),c.startsAt().plusNanos(1),c.endsAt(),"非整秒"))) {
            fails(() -> handoffs.request(bad,2,"test"), "ONCALL_HANDOFF_INVALID");
        }
        fails(() -> handoffs.request(new OnCallHandoffService.Command(c.sourceShiftId(),1,3,UUID.randomUUID().toString(),c.startsAt(),c.endsAt(),"旧版"),2,"test"), "ONCALL_HANDOFF_SOURCE_CHANGED");
        assertThat(handoffs.list(f.schedule()).requests()).hasSize(1);
    }

    @Test
    void shouldRollBackOverrideDecisionAndAuditsTogether() {
        var f = fixture(false);
        var pending = handoffs.request(command(f),2,"test");
        long before = audits();
        doAnswer(call -> { throw new IllegalStateException("handoff-rollback-sentinel"); })
                .when(audit).recordAs(eq(3L),eq("test"),eq("ONCALL_HANDOFF_ACCEPTED"),eq("ONCALL_HANDOFF"),eq(pending.id()),anyString());
        assertThatThrownBy(() -> handoffs.decide(pending.id(),decision("ACCEPTED"),3,"test"))
                .isInstanceOf(IllegalStateException.class).hasMessage("handoff-rollback-sentinel");
        assertThat(audits()).isEqualTo(before);
        assertThat(shifts(f)).isEqualTo(1);
        assertThat(handoffs.list(f.schedule()).requests().get(0).status()).isEqualTo("PENDING");
        assertThat(handoffs.list(f.schedule()).requests().get(0).replacementShiftId()).isNull();
    }

    @Test
    void shouldSerializeConcurrentRequestRetriesAndAcceptRetries() throws Exception {
        var f = fixture(false);
        var c = command(f);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            var a = pool.submit(() -> { await(gate); return handoffs.request(c,2,"test"); });
            var b = pool.submit(() -> { await(gate); return handoffs.request(c,2,"test"); });
            gate.countDown();
            long id = a.get(15,TimeUnit.SECONDS).id();
            assertThat(b.get(15,TimeUnit.SECONDS).id()).isEqualTo(id);
            long before = audits();
            var acceptGate = new CountDownLatch(1);
            var x = pool.submit(() -> { await(acceptGate); return handoffs.decide(id,decision("ACCEPTED"),3,"test"); });
            var y = pool.submit(() -> { await(acceptGate); return handoffs.decide(id,decision("ACCEPTED"),3,"test"); });
            acceptGate.countDown();
            assertThat(x.get(15,TimeUnit.SECONDS)).isEqualTo(y.get(15,TimeUnit.SECONDS));
            assertThat(shifts(f)).isEqualTo(2);
            assertThat(audits()).isEqualTo(before+2);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void shouldAllowOnlyOneWinnerBetweenAcceptAndWithdraw() throws Exception {
        var f = fixture(false);
        var pending = handoffs.request(command(f),2,"test");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            var a = pool.submit(() -> { await(gate); return outcome(pending.id(),"ACCEPTED",3); });
            var b = pool.submit(() -> { await(gate); return outcome(pending.id(),"WITHDRAWN",2); });
            gate.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)))
                    .contains("ONCALL_HANDOFF_VERSION_CONFLICT");
            var result = handoffs.list(f.schedule()).requests().get(0);
            assertThat(result.status()).isIn("ACCEPTED","WITHDRAWN");
            assertThat(result.version()).isEqualTo(1);
            assertThat(shifts(f)).isEqualTo(result.status().equals("ACCEPTED") ? 2 : 1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void shouldNotAcceptTwoDifferentRequestsForTheSameSourceWindow() throws Exception {
        var f = fixture(false);
        var first = handoffs.request(command(f),2,"test");
        var second = handoffs.request(command(f),2,"test");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var gate = new CountDownLatch(1);
            var a = pool.submit(() -> { await(gate); return outcome(first.id(),"ACCEPTED",3); });
            var b = pool.submit(() -> { await(gate); return outcome(second.id(),"ACCEPTED",3); });
            gate.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ACCEPTED","ONCALL_HANDOFF_OVERLAP");
            assertThat(shifts(f)).isEqualTo(2);
            assertThat(handoffs.list(f.schedule()).requests()).extracting(OnCallHandoffService.View::status)
                    .containsExactlyInAnyOrder("ACCEPTED","PENDING");
        } finally { pool.shutdownNow(); }
    }

    @Test
    void shouldObserveSourceCancellationCommittedBeforeScheduleLock() throws Exception {
        var f = fixture(false);
        var pending = handoffs.request(command(f),2,"test");
        var ready = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(call -> { ready.countDown(); await(resume); return call.callRealMethod(); })
                .when(jdbc).sql(eq("SELECT active FROM oncall_schedule WHERE id=:id FOR UPDATE"));
        var pool = Executors.newSingleThreadExecutor();
        try {
            var result = pool.submit(() -> outcome(pending.id(),"ACCEPTED",3));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();
            roster.cancel(f.source().id(),0,"已提交取消",1L,"test");
            resume.countDown();
            assertThat(result.get(15,TimeUnit.SECONDS)).isEqualTo("ONCALL_HANDOFF_SOURCE_CHANGED");
            assertThat(shifts(f)).isEqualTo(1);
        } finally { resume.countDown(); pool.shutdownNow(); }
    }

    @Test
    void shouldClampOngoingCoverageAfterActualLockWaitAndRouteOnlyNewIncidents() throws Exception {
        var f = fixture(true);
        long policy = insert(jdbc.sql("INSERT INTO escalation_policy(service_resource_id,name,severity) VALUES (:id,'接班测试','P1')").param("id",f.resource()));
        jdbc.sql("INSERT INTO escalation_step(policy_id,step_order,delay_minutes,target_type,target_ref) VALUES (:id,1,0,'ON_CALL',:target)")
                .param("id",policy).param("target","schedule:"+f.schedule()).update();
        var before = intake(f,"接班前");
        assertThat(recipient(before)).isEqualTo("zhangwei");
        var pending = handoffs.request(command(f),2,"test");
        var ready = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocker = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                jdbc.sql("SELECT id FROM oncall_schedule WHERE id=:id FOR UPDATE").param("id",f.schedule()).query(Long.class).single();
                ready.countDown(); await(resume); return true;
            }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();
            var waiting = new CountDownLatch(1);
            doAnswer(call -> { waiting.countDown(); return call.callRealMethod(); })
                    .when(jdbc).sql(eq("SELECT active FROM oncall_schedule WHERE id=:id FOR UPDATE"));
            var accepting = pool.submit(() -> handoffs.decide(pending.id(),decision("ACCEPTED"),3,"test"));
            assertThat(waiting.await(10,TimeUnit.SECONDS)).isTrue();
            Thread.sleep(1100); // Real database lock is held across a whole-second boundary.
            LocalDateTime releasedAt = now();
            resume.countDown(); blocker.get(15,TimeUnit.SECONDS);
            var accepted = accepting.get(15,TimeUnit.SECONDS);
            var cover = replacement(accepted);
            assertThat(cover.startsAt()).isAfterOrEqualTo(releasedAt);
            assertThat(coverage.coverage(f.schedule(),f.source().startsAt(),cover.startsAt()).segments())
                    .allSatisfy(s -> assertThat(s.userId()).isEqualTo(2L));
            // Acceptance rounds up, so wait boundedly until the real override becomes active.
            long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while (now().isBefore(cover.startsAt()) && System.nanoTime()<deadline) Thread.sleep(25);
            assertThat(current.current().stream().filter(s -> s.scheduleId()==f.schedule()).findFirst().orElseThrow().userId()).isEqualTo(3L);
            // Otherwise the two-hour same-service aggregation correctly attaches the
            // second alert to the existing incident and must not rewrite its old route.
            jdbc.sql("UPDATE incident SET status='RESOLVED',resolved_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",before).update();
            var after = intake(f,"接班后");
            assertThat(after).isNotEqualTo(before);
            assertThat(recipient(after)).isEqualTo("lina");
            assertThat(recipient(before)).isEqualTo("zhangwei");
        } finally { resume.countDown(); pool.shutdownNow(); }
    }

    @Test
    void shouldRejectExpiredRequestsWithoutMutatingTheirHistory() {
        var f = fixture(false);
        var pending = handoffs.request(command(f),2,"test");
        // Test-only passage of time: do not claim an automatic expiration scheduler exists.
        jdbc.sql("UPDATE oncall_handoff SET starts_at=:start,ends_at=:end WHERE id=:id")
                .param("start",now().minusHours(2)).param("end",now().minusHours(1)).param("id",pending.id()).update();
        // Create a matching past source window so expiration, not subset validation, is tested.
        jdbc.sql("UPDATE oncall_shift SET starts_at=:start,ends_at=:end WHERE id=:id")
                .param("start",now().minusHours(3)).param("end",now().plusHours(1)).param("id",f.source().id()).update();
        fails(() -> handoffs.decide(pending.id(),decision("ACCEPTED"),3,"test"),"ONCALL_HANDOFF_EXPIRED");
        assertThat(handoffs.list(f.schedule()).requests().get(0).status()).isEqualTo("PENDING");
        assertThat(shifts(f)).isEqualTo(1);
        handoffs.decide(pending.id(),decision("WITHDRAWN"),2,"test");
    }

    @Test
    void shouldEnforceHttpRolesAndParticipantIdentity() throws Exception {
        var f = fixture(false);
        String payload = json.writeValueAsString(command(f));
        mvc.perform(post("/api/v1/on-call/handoffs").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isUnauthorized());
        String auditor = login("auditor");
        mvc.perform(get("/api/v1/on-call/handoffs").header("Authorization",auditor)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/on-call/handoffs").header("Authorization",auditor).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isForbidden());
        String actor = login("zhangwei");
        var response = mvc.perform(post("/api/v1/on-call/handoffs").header("Authorization",actor).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PENDING")).andReturn().getResponse().getContentAsString();
        long id = json.readTree(response).path("data").path("id").asLong();
        String accept = json.writeValueAsString(decision("ACCEPTED"));
        mvc.perform(post("/api/v1/on-call/handoffs/"+id+"/decisions").header("Authorization",actor).contentType(MediaType.APPLICATION_JSON).content(accept)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/on-call/handoffs/"+id+"/decisions").header("Authorization",login("lina")).contentType(MediaType.APPLICATION_JSON).content(accept))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACCEPTED"));
    }

    @Test
    void shouldExposeListTruncationInsteadOfSilentlyDroppingRequests() {
        var f = fixture(false);
        for (int i=0;i<201;i++) handoffs.request(command(f),2,"test");
        var list = handoffs.list(f.schedule());
        assertThat(list.requests()).hasSize(200);
        assertThat(list.truncated()).isTrue();
        assertThat(list.databaseNow()).isNotNull();
        assertThat(handoffs.list(-1L).requests()).isEmpty();
    }

    @Test
    void shouldFilterParticipantsAndStatusBeforeTheListLimit() throws Exception {
        var f = fixture(false);
        var outgoing = handoffs.request(command(f),2,"test");
        var withdrawn = handoffs.request(command(f),2,"test");
        handoffs.decide(withdrawn.id(),decision("WITHDRAWN"),2,"test");
        var otherSource = roster.create(new OnCallRosterService.ShiftCommand(f.schedule(),3,
                f.source().endsAt(),f.source().endsAt().plusHours(4),false,"另一负责人的班次"),1L,"test");
        var incoming = handoffs.request(new OnCallHandoffService.Command(otherSource.id(),0,2,
                UUID.randomUUID().toString(),otherSource.startsAt(),otherSource.endsAt(),"定向请求"),3,"test");
        // Real business writes place 201 newer, unrelated requests ahead of this user's work.
        for (int i=0;i<201;i++) handoffs.request(new OnCallHandoffService.Command(otherSource.id(),0,1,
                UUID.randomUUID().toString(),otherSource.startsAt(),otherSource.endsAt(),"其他人的待办"),3,"test");
        String actor = login("zhangwei");
        mvc.perform(get("/api/v1/on-call/handoffs").header("Authorization",actor)
                        .param("scheduleId",String.valueOf(f.schedule())).param("scope","MINE").param("status","PENDING")
                        .param("participantId","3")) // The server must derive MINE from authentication, not caller input.
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.requests.length()").value(2))
                .andExpect(jsonPath("$.data.requests[0].id").value(incoming.id()))
                .andExpect(jsonPath("$.data.requests[1].id").value(outgoing.id()));
        mvc.perform(get("/api/v1/on-call/handoffs").header("Authorization",actor)
                        .param("scheduleId",String.valueOf(f.schedule())).param("scope","MINE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.requests.length()").value(3));
        mvc.perform(get("/api/v1/on-call/handoffs").header("Authorization",actor)
                        .param("scheduleId",String.valueOf(f.schedule())).param("status","WITHDRAWN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.requests.length()").value(1))
                .andExpect(jsonPath("$.data.requests[0].id").value(withdrawn.id()));
        mvc.perform(get("/api/v1/on-call/handoffs").header("Authorization",actor)
                        .param("scheduleId",String.valueOf(f.schedule())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.requests.length()").value(200))
                .andExpect(jsonPath("$.data.truncated").value(true));
        mvc.perform(get("/api/v1/on-call/handoffs").header("Authorization",login("auditor"))
                        .param("scheduleId",String.valueOf(f.schedule())).param("scope","MINE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.requests.length()").value(0));
    }

    @Test
    void shouldRejectInvalidListFiltersInsteadOfSilentlyBroadeningTheQuery() throws Exception {
        mvc.perform(get("/api/v1/on-call/handoffs").param("scope","MINE")).andExpect(status().isUnauthorized());
        String actor = login("zhangwei");
        for (var filter : List.of(Map.entry("scope","OTHERS"),Map.entry("scope","mine"),
                Map.entry("status","EXPIRED"),Map.entry("status","pending"),Map.entry("status",""))) {
            mvc.perform(get("/api/v1/on-call/handoffs").header("Authorization",actor).param(filter.getKey(),filter.getValue()))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("ONCALL_HANDOFF_INVALID"));
        }
    }

    @Test
    void shouldRevokeCoverageOnceWithoutRewritingAcceptedConsentOrSource() {
        var f = fixture(false);
        var accepted = accepted(f);
        var before = handoffs.coverage(accepted.id());
        assertThat(before.request()).isEqualTo(accepted);
        assertThat(before.revocation()).isNull();
        assertThat(before.replacement().cancelledAt()).isNull();
        var command = revocation(accepted);
        long auditCount = audits();
        var after = handoffs.revokeCoverage(accepted.id(), command, 1, "test");
        assertThat(after.request()).isEqualTo(accepted);
        assertThat(after.replacement().version()).isEqualTo(1);
        assertThat(after.replacement().cancelledAt()).isNotNull();
        assertThat(after.revocation().actorId()).isEqualTo(1);
        assertThat(after.revocation().operationKey()).isEqualTo(command.operationKey());
        assertThat(after.revocation().reason()).isEqualTo(command.reason());
        assertThat(handoffs.revokeCoverage(accepted.id(), command, 1, "test").revocation()).isEqualTo(after.revocation());
        assertThat(handoffs.decide(accepted.id(), decision("ACCEPTED"), 3, "test")).isEqualTo(accepted);
        assertThat(audits()).isEqualTo(auditCount + 2);
        assertThat(shifts(f)).isEqualTo(2);
        assertThat(roster.roster(f.schedule(), f.source().startsAt(), f.source().endsAt()).shifts())
                .contains(f.source());
        assertThat(coverage.coverage(f.schedule(), f.source().startsAt(), f.source().endsAt()).segments())
                .allSatisfy(segment -> assertThat(segment.userId()).isEqualTo(2));
    }

    @Test
    void shouldRestrictRevocationToCurrentActiveManagersIncludingOldJwt() throws Exception {
        var accepted = accepted(fixture(false));
        String route = "/api/v1/on-call/handoffs/" + accepted.id() + "/coverage/revoke";
        String payload = json.writeValueAsString(revocation(accepted));
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isUnauthorized());
        for (String user : List.of("zhangwei", "auditor")) {
            mvc.perform(post(route).header("Authorization", login(user)).contentType(MediaType.APPLICATION_JSON)
                    .content(payload)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/on-call/handoffs/" + accepted.id() + "/coverage")
                .header("Authorization", login("auditor"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.request.status").value("ACCEPTED"));
        String manager = login("lina");
        jdbc.sql("UPDATE sys_user SET role_code='ON_CALL' WHERE id=3").update();
        try {
            mvc.perform(post(route).header("Authorization", manager).contentType(MediaType.APPLICATION_JSON)
                    .content(payload)).andExpect(status().isForbidden());
        } finally { jdbc.sql("UPDATE sys_user SET role_code='OPS_MANAGER' WHERE id=3").update(); }
        jdbc.sql("UPDATE sys_user SET status='INACTIVE' WHERE id=3").update();
        try { fails(() -> handoffs.revokeCoverage(accepted.id(), revocation(accepted), 3, "test"), "ONCALL_HANDOFF_FORBIDDEN"); }
        finally { jdbc.sql("UPDATE sys_user SET status='ACTIVE' WHERE id=3").update(); }
        mvc.perform(post(route).header("Authorization", manager).contentType(MediaType.APPLICATION_JSON)
                .content(payload)).andExpect(status().isOk()).andExpect(jsonPath("$.data.revocation.actorId").value(3));
    }

    @Test
    void shouldRejectInvalidRevocationAndStaleVersionsBeforeMutation() {
        var f = fixture(false);
        var pending = handoffs.request(command(f), 2, "test");
        assertThat(handoffs.coverage(pending.id()).replacement()).isNull();
        fails(() -> handoffs.revokeCoverage(pending.id(), new OnCallHandoffService.RevocationCommand(0, 0, UUID.randomUUID().toString(), "说明"), 1, "test"), "ONCALL_HANDOFF_VERSION_CONFLICT");
        var accepted = handoffs.decide(pending.id(), decision("ACCEPTED"), 3, "test");
        var valid = revocation(accepted);
        for (var invalid : List.of(
                new OnCallHandoffService.RevocationCommand(1, 0, "invalid", "说明"),
                new OnCallHandoffService.RevocationCommand(-1, 0, valid.operationKey(), "说明"),
                new OnCallHandoffService.RevocationCommand(1, 0, valid.operationKey(), " "))) {
            fails(() -> handoffs.revokeCoverage(accepted.id(), invalid, 1, "test"), "ONCALL_HANDOFF_INVALID");
        }
        fails(() -> handoffs.revokeCoverage(accepted.id(), new OnCallHandoffService.RevocationCommand(0, 0, valid.operationKey(), "说明"), 1, "test"), "ONCALL_HANDOFF_VERSION_CONFLICT");
        fails(() -> handoffs.revokeCoverage(accepted.id(), new OnCallHandoffService.RevocationCommand(1, 1, valid.operationKey(), "说明"), 1, "test"), "ONCALL_SHIFT_VERSION_CONFLICT");
        assertThat(handoffs.coverage(accepted.id()).replacement().cancelledAt()).isNull();
    }

    @Test
    void shouldExposeExternalCancellationWithoutInventingARevocationRecord() {
        var accepted = accepted(fixture(false));
        roster.cancel(accepted.replacementShiftId(), 0, "班次维护直接取消", 1L, "test");
        var actual = handoffs.coverage(accepted.id());
        assertThat(actual.request()).isEqualTo(accepted);
        assertThat(actual.replacement().cancellationReason()).isEqualTo("班次维护直接取消");
        assertThat(actual.revocation()).isNull();
        fails(() -> handoffs.revokeCoverage(accepted.id(), revocation(accepted), 1, "test"), "ONCALL_SHIFT_VERSION_CONFLICT");
    }

    @Test
    void shouldRefuseNewRevocationAfterEndButAcknowledgePreviouslyCommittedCommand() {
        var accepted = accepted(fixture(false));
        jdbc.sql("UPDATE oncall_shift SET starts_at=:start,ends_at=:end WHERE id=:id")
                .param("start", now().minusHours(2)).param("end", now().minusHours(1)).param("id", accepted.replacementShiftId()).update();
        fails(() -> handoffs.revokeCoverage(accepted.id(), revocation(accepted), 1, "test"), "ONCALL_HANDOFF_EXPIRED");
        var second = accepted(fixture(false));
        var command = revocation(second);
        var saved = handoffs.revokeCoverage(second.id(), command, 1, "test").revocation();
        jdbc.sql("UPDATE oncall_shift SET starts_at=:start,ends_at=:end WHERE id=:id")
                .param("start", now().minusHours(2)).param("end", now().minusHours(1)).param("id", second.replacementShiftId()).update();
        assertThat(handoffs.revokeCoverage(second.id(), command, 1, "test").revocation()).isEqualTo(saved);
    }

    @Test
    void shouldRejectReusedRevocationKeyAndDifferentIntentAfterCancellation() {
        var first = accepted(fixture(false));
        var second = accepted(fixture(false));
        var command = revocation(first);
        handoffs.revokeCoverage(first.id(), command, 1, "test");
        fails(() -> handoffs.revokeCoverage(second.id(), command, 1, "test"), "ONCALL_HANDOFF_REVOCATION_KEY_REUSED");
        fails(() -> handoffs.revokeCoverage(first.id(), new OnCallHandoffService.RevocationCommand(1, 0, command.operationKey(), "不同说明"), 1, "test"), "ONCALL_HANDOFF_REVOCATION_KEY_REUSED");
        fails(() -> handoffs.revokeCoverage(first.id(), revocation(first), 1, "test"), "ONCALL_SHIFT_VERSION_CONFLICT");
        fails(() -> handoffs.revokeCoverage(first.id(), command, 3, "test"), "ONCALL_SHIFT_VERSION_CONFLICT");
        assertThat(handoffs.coverage(second.id()).replacement().cancelledAt()).isNull();
    }

    @Test
    void shouldRollbackCancellationLedgerAndBothAuditsTogether() {
        var accepted = accepted(fixture(false));
        long count = audits();
        doAnswer(call -> { throw new IllegalStateException("revocation-rollback-sentinel"); })
                .when(audit).recordAs(eq(1L), eq("test"), eq("ONCALL_HANDOFF_COVERAGE_REVOKED"), eq("ONCALL_HANDOFF"), eq(accepted.id()), anyString());
        assertThatThrownBy(() -> handoffs.revokeCoverage(accepted.id(), revocation(accepted), 1, "test"))
                .hasMessage("revocation-rollback-sentinel");
        var current = handoffs.coverage(accepted.id());
        assertThat(current.request()).isEqualTo(accepted);
        assertThat(current.replacement().cancelledAt()).isNull();
        assertThat(current.replacement().version()).isZero();
        assertThat(current.revocation()).isNull();
        assertThat(audits()).isEqualTo(count);
    }

    @Test
    void shouldSerializeConcurrentSameKeyRevocationWithOnlyOneChange() throws Exception {
        var accepted = accepted(fixture(false));
        var command = revocation(accepted);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        long count = audits();
        try {
            java.util.concurrent.Callable<OnCallHandoffService.Revocation> action = () -> { await(start); return handoffs.revokeCoverage(accepted.id(), command, 1, "test").revocation(); };
            var first = executor.submit(action); var second = executor.submit(action); start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
            assertThat(handoffs.coverage(accepted.id()).replacement().version()).isEqualTo(1);
            assertThat(audits()).isEqualTo(count + 2);
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test
    void shouldNotPromiseOriginalCoverageWhenOriginalShiftWasAlsoCancelled() {
        var f = fixture(false);
        var accepted = accepted(f);
        roster.cancel(f.source().id(), 0, "原负责人也不可值班", 1L, "test");
        jdbc.sql("UPDATE oncall_schedule SET active=FALSE WHERE id=:id").param("id", f.schedule()).update();
        // Cleanup remains available even on an inactive plan; no claim of restored routing.
        var result = handoffs.revokeCoverage(accepted.id(), revocation(accepted), 1, "test");
        assertThat(result.request()).isEqualTo(accepted);
        assertThat(result.revocation()).isNotNull();
        assertThat(roster.roster(f.schedule(), f.source().startsAt(), f.source().endsAt()).shifts())
                .allSatisfy(shift -> assertThat(shift.cancelledAt()).isNotNull());
    }

    @Test
    void shouldSerializeDifferentManagerRevocationsWithoutDoubleAudits() throws Exception {
        var accepted = accepted(fixture(false));
        var firstCommand = revocation(accepted);
        var secondCommand = revocation(accepted);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        long count = audits();
        try {
            var first = executor.submit(() -> { await(start); return revokeOutcome(accepted.id(), firstCommand, 1); });
            var second = executor.submit(() -> { await(start); return revokeOutcome(accepted.id(), secondCommand, 3); });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("REVOKED", "ONCALL_SHIFT_VERSION_CONFLICT");
            assertThat(handoffs.coverage(accepted.id()).replacement().version()).isEqualTo(1);
            assertThat(audits()).isEqualTo(count + 2);
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    private String revokeOutcome(long id, OnCallHandoffService.RevocationCommand command, long actor) {
        try { handoffs.revokeCoverage(id, command, actor, "test"); return "REVOKED"; }
        catch (ApiException error) { return error.code(); }
    }

    private OnCallHandoffService.View accepted(Fixture f) {
        var pending = handoffs.request(command(f), 2, "test");
        return handoffs.decide(pending.id(), decision("ACCEPTED"), 3, "test");
    }
    private OnCallHandoffService.RevocationCommand revocation(OnCallHandoffService.View accepted) {
        return new OnCallHandoffService.RevocationCommand(accepted.version(), 0, UUID.randomUUID().toString(), "管理确认撤销覆盖");
    }

    private Fixture fixture(boolean ongoing) {
        String code = "HANDOFF-"+UUID.randomUUID();
        long resource = insert(jdbc.sql("INSERT INTO cmdb_resource(resource_code,resource_type,name,environment,status) VALUES (:code,'APPLICATION','接班独立服务','TEST','RUNNING')").param("code",code));
        long schedule = insert(jdbc.sql("INSERT INTO oncall_schedule(service_resource_id,name) VALUES (:id,'接班独立计划')").param("id",resource));
        var at = now().truncatedTo(ChronoUnit.SECONDS);
        var start = ongoing ? at.minusHours(1) : at.plusDays(1);
        var source = roster.create(new OnCallRosterService.ShiftCommand(schedule,2,start,start.plusHours(4),false,"接班源班次"),1L,"test");
        return new Fixture(resource,code,schedule,source);
    }
    private OnCallHandoffService.Command command(Fixture f) { return new OnCallHandoffService.Command(f.source().id(),f.source().version(),3,UUID.randomUUID().toString(),f.source().startsAt(),f.source().endsAt(),"临时接班"); }
    private OnCallHandoffService.Decision decision(String status) { return new OnCallHandoffService.Decision(0,status,"接班决定"); }
    private String outcome(long id,String status,long actor) { try { return handoffs.decide(id,decision(status),actor,"test").status(); } catch (ApiException e) { return e.code(); } }
    private void fails(Runnable action,String code) { assertThatThrownBy(action::run).isInstanceOf(ApiException.class).extracting("code").isEqualTo(code); }
    private long audits() { return jdbc.sql("SELECT COUNT(*) FROM audit_log").query(Long.class).single(); }
    private long shifts(Fixture f) { return jdbc.sql("SELECT COUNT(*) FROM oncall_shift WHERE schedule_id=:id").param("id",f.schedule()).query(Long.class).single(); }
    private LocalDateTime now() { return jdbc.sql("SELECT CURRENT_TIMESTAMP(6)").query((rs,row)->rs.getObject(1,LocalDateTime.class)).single(); }
    private OnCallRosterService.ShiftView replacement(OnCallHandoffService.View v) { return roster.roster(v.scheduleId(),v.startsAt(),v.endsAt()).shifts().stream().filter(s -> s.id()==v.replacementShiftId()).findFirst().orElseThrow(); }
    private long intake(Fixture f,String title) { return alerts.intake(new AlertService.IntakeRequest("handoff-test",UUID.randomUUID().toString(),f.code(),"P1","FIRING",title,"真实接入",Map.of(),now())).incidentId(); }
    private String recipient(long incident) { return jdbc.sql("SELECT recipient FROM incident_escalation_event WHERE incident_id=:id").param("id",incident).query(String.class).single(); }
    private long insert(JdbcClient.StatementSpec stmt) { var key = new GeneratedKeyHolder(); stmt.update(key,"id"); return key.getKey().longValue(); }
    private void await(CountDownLatch latch) { try { if (!latch.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("handoff barrier timed out"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
    private String login(String username) throws Exception { String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\""+username+"\",\"password\":\"OpsPilot@2026\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(); return "Bearer "+json.readTree(response).path("data").path("accessToken").asText(); }
    private record Fixture(long resource,String code,long schedule,OnCallRosterService.ShiftView source) {}
}
