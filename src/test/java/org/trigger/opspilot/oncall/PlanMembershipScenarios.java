package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** New plans start without grants; these same cases run on H2 and owned MySQL. */
abstract class PlanMembershipScenarios {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource datasource;
    @Autowired OnCallPlanMembershipService members;
    @Autowired OnCallRosterService roster;
    @Autowired OnCallCoverageService coverage;
    @Autowired OnCallRotationService rotations;
    @Autowired OnCallOpenHandoffService open;
    @Autowired OnCallHandoffService handoffs;
    @Autowired OnCallSwapService swaps;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @SpyBean AuditService audit;

    @BeforeEach void databaseIdentity(TestInfo test) throws Exception {
        try (var connection = datasource.getConnection()) {
            var metadata = connection.getMetaData();
            if (getClass().getSimpleName().startsWith("MySql")) {
                assertThat(metadata.getDatabaseProductName()).isEqualTo("MySQL");
                assertThat(metadata.getDatabaseProductVersion()).startsWith("8.4.");
                assertThat(connection.getCatalog()).isEqualTo("opspilot_plan_membership_test");
            }
            assertThat(jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version='39' AND success=TRUE").query(Long.class).single()).isEqualTo(1);
            System.out.println("PLAN_MEMBERSHIP_DATABASE " + json.writeValueAsString(Map.of("case", test.getTestMethod().orElseThrow().getName(),
                    "product", metadata.getDatabaseProductName(), "version", metadata.getDatabaseProductVersion(), "schema", connection.getCatalog(), "migration39", true)));
        }
    }
    @AfterEach void resetAudit() { reset(audit); }

    @Test void shouldBackfillOnlyPreexistingOperationalUsersAndNeverEnrolNewPlansOrAccounts() {
        var migrated = jdbc.sql("SELECT * FROM oncall_schedule_member WHERE origin='MIGRATED_GLOBAL_V38'").query().listOfRows();
        assertThat(migrated).isNotEmpty().allSatisfy(row -> {
            long user = ((Number) row.get("user_id")).longValue();
            assertThat(user).isIn(1L, 2L, 3L);
            assertThat(row.get("can_respond")).isEqualTo(true);
            assertThat(row.get("can_manage")).isEqualTo(user == 1 || user == 3);
        });
        long plan = plan(), user = user();
        assertThat(members.list(plan)).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM oncall_schedule_member WHERE user_id=:user").param("user", user).query(Long.class).single()).isZero();
        denied(() -> roster.createManaged(shift(plan, user, false), 1, "test"), "ONCALL_PLAN_RESPONSE_FORBIDDEN");
        grant(plan, user, true, false);
        assertThat(roster.createManaged(shift(plan, user, false), 1, "test").userId()).isEqualTo(user);
    }

    @Test void shouldSeparateManagementFromResponseAndKeepAdminRecoveryWithoutSelfClaim() {
        long plan = plan(); grant(plan, 2, true, false); grant(plan, 3, false, true);
        var source = roster.createManaged(shift(plan, 2, false), 3, "test");
        var row = publish(source);
        denied(() -> open.claim(row.id(), operation(), 3, "test"), "ONCALL_PLAN_RESPONSE_FORBIDDEN");
        denied(() -> open.claim(row.id(), operation(), 1, "test"), "ONCALL_PLAN_RESPONSE_FORBIDDEN");
        denied(() -> members.change(plan, command(2, 0, true, true, false), 2, "test"), "ONCALL_PLAN_MANAGEMENT_FORBIDDEN");
        assertThat(open.get(row.id()).status()).isEqualTo("OPEN");
        grant(plan, 3, true, true);
        assertThat(open.claim(row.id(), operation(), 3, "test").request().status()).isEqualTo("CLAIMED");
    }

    @Test void shouldDenyAnotherValidPlanAndPermitItsExplicitMemberControl() {
        long first = plan(), second = plan(); grant(first, 2, true, false); grant(second, 2, true, false); grant(first, 3, true, true);
        var a = publish(roster.createManaged(shift(first, 2, false), 1, "test"));
        var b = publish(roster.createManaged(shift(second, 2, false), 1, "test"));
        var key = operation(); long before = audits();
        denied(() -> open.claim(b.id(), key, 3, "test"), "ONCALL_PLAN_RESPONSE_FORBIDDEN");
        denied(() -> members.change(second, command(2, 0, true, true, false), 3, "test"), "ONCALL_PLAN_MANAGEMENT_FORBIDDEN");
        assertThat(audits()).isEqualTo(before);
        assertThat(open.claim(a.id(), operation(), 3, "test").request().claimedBy()).isEqualTo(3L);
        grant(second, 3, true, false);
        assertThat(open.claim(b.id(), key, 3, "test").request().claimedBy()).isEqualTo(3L);
    }

    @Test void shouldRejectMissingExpectedVersionButAcceptExplicitNullAndSerializeEffectivePermissions() throws Exception {
        long plan = plan(); ObjectNode body = json.valueToTree(command(3, null, true, false, true));
        String admin = login("admin"); ObjectNode missing = body.deepCopy(); missing.remove("expectedVersion");
        mvc.perform(post(base(plan)).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(missing.toString()))
                .andExpect(status().isBadRequest());
        assertThat(members.list(plan)).isEmpty();
        mvc.perform(post(base(plan)).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.current.version").value(0))
                .andExpect(jsonPath("$.data.current.effectiveResponse").value(false)).andExpect(jsonPath("$.data.current.effectiveManagement").value(true));
        mvc.perform(get(base(plan)).header("Authorization", login("auditor"))).andExpect(status().isOk());
        mvc.perform(post(base(plan)).header("Authorization", login("auditor")).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(get(base(plan))).andExpect(status().isUnauthorized());
    }

    @Test void shouldRecordActualActorIpAndOldAndNewPermissions() {
        long plan = plan(); grant(plan, 3, false, true);
        members.change(plan, command(3, 0, true, true, false), 1, "198.51.100.9");
        var row = jdbc.sql("SELECT actor_id,ip_address,detail FROM audit_log WHERE action='ONCALL_MEMBER_CHANGED' AND target_id=:plan ORDER BY id DESC LIMIT 1")
                .param("plan", Long.toString(plan)).query().singleRow();
        assertThat(((Number) row.get("actor_id")).longValue()).isEqualTo(1);
        assertThat(row.get("ip_address")).isEqualTo("198.51.100.9");
        assertThat(row.get("detail").toString()).contains("old[active=true,respond=false,manage=true]", "new[active=true,respond=true,manage=false]");
    }

    @Test void shouldCommitOneImmutableReceiptForConcurrentOriginalCommands() throws Exception {
        long plan = plan(); var command = command(3, null, true, true, true); long before = audits();
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return members.change(plan, command, 1, "test"); });
            var b = executor.submit(() -> { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return members.change(plan, command, 1, "test"); });
            start.countDown(); assertThat(a.get(15, TimeUnit.SECONDS).receipt()).isEqualTo(b.get(15, TimeUnit.SECONDS).receipt());
            assertThat(audits()).isEqualTo(before + 1);
            assertThat(operations(plan)).isEqualTo(1);
            assertThat(members.list(plan)).singleElement().satisfies(m -> assertThat(m.version()).isZero());
        } finally { start.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void shouldRejectCrossPlanOrChangedKeyAndStaleOrOverflowedVersions() {
        long plan = plan(), other = plan(); var original = command(3, null, true, true, true);
        members.change(plan, original, 1, "test");
        denied(() -> members.change(other, original, 1, "test"), "ONCALL_MEMBER_KEY_REUSED");
        denied(() -> members.change(plan, new OnCallPlanMembershipService.Command(3, null, true, false, true, original.operationKey(), original.reason()), 1, "test"), "ONCALL_MEMBER_KEY_REUSED");
        denied(() -> members.change(plan, command(3, null, true, true, true), 1, "test"), "ONCALL_MEMBER_VERSION_CONFLICT");
        members.change(plan, command(3, 0, true, false, true), 1, "test");
        denied(() -> members.change(plan, command(3, 0, true, true, true), 1, "test"), "ONCALL_MEMBER_VERSION_CONFLICT");
        jdbc.sql("UPDATE oncall_schedule_member SET version=2147483647 WHERE schedule_id=:plan").param("plan", plan).update();
        denied(() -> members.change(plan, command(3, Integer.MAX_VALUE, false, false, false), 1, "test"), "ONCALL_MEMBER_VERSION_CONFLICT");
        assertThat(operations(plan)).isEqualTo(2);
    }

    @Test void shouldPermitOriginalManagerReceiptAfterOwnPlanManagementWasRevoked() {
        long plan = plan(); grant(plan, 3, false, true); var original = command(2, null, true, true, false);
        var saved = members.change(plan, original, 3, "test"); revoke(plan, 3); long before = audits();
        assertThat(members.change(plan, original, 3, "test").receipt()).isEqualTo(saved.receipt());
        denied(() -> members.change(plan, command(2, 0, false, false, false), 3, "test"), "ONCALL_PLAN_MANAGEMENT_FORBIDDEN");
        assertThat(audits()).isEqualTo(before);
    }

    @Test void shouldRollbackFirstGrantAfterActualAuditInsertFails() { rollback(false, false); }
    @Test void shouldRollbackUpdateAfterActualAuditInsertFails() { rollback(true, false); }
    @Test void shouldRollbackRevocationAfterActualAuditInsertFails() { rollback(true, true); }
    private void rollback(boolean existing, boolean revoke) {
        long plan = plan(); if (existing) grant(plan, 3, false, true);
        var before = members.list(plan); long auditBefore = audits(), operationBefore = operations(plan);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("member-audit-rollback"); }).when(audit)
                .recordAs(eq(1L), eq("test"), eq("ONCALL_MEMBER_CHANGED"), eq("ONCALL_SCHEDULE"), eq(plan), anyString());
        assertThatThrownBy(() -> members.change(plan, command(3, existing ? 0 : null, !revoke, !revoke, !revoke), 1, "test"))
                .hasMessage("member-audit-rollback");
        assertThat(members.list(plan)).isEqualTo(before); assertThat(audits()).isEqualTo(auditBefore); assertThat(operations(plan)).isEqualTo(operationBefore);
    }

    @Test void shouldRetainAssignedCoverageAndOriginalClaimReceiptAfterMembershipRevocation() {
        long plan = plan(); grant(plan, 2, true, false); grant(plan, 3, true, false);
        var source = roster.createManaged(shift(plan, 2, false), 1, "test"); var request = publish(source); var original = operation();
        var receipt = open.claim(request.id(), original, 3, "test"); revoke(plan, 3); long before = audits();
        assertThat(open.claim(request.id(), original, 3, "test").operation()).isEqualTo(receipt.operation());
        assertThat(open.coverage(request.id()).replacement().cancelledAt()).isNull();
        assertThat(coverage.coverage(plan, receipt.replacement().startsAt(), receipt.replacement().endsAt()).segments())
                .isNotEmpty().allSatisfy(segment -> assertThat(segment.userId()).isEqualTo(3L));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM oncall_shift WHERE id=:id AND cancelled_at IS NULL").param("id", source.id()).query(Long.class).single()).isEqualTo(1);
        denied(() -> open.claim(request.id(), operation(), 3, "test"), "ONCALL_OPEN_HANDOFF_VERSION_CONFLICT");
        assertThat(audits()).isEqualTo(before);
    }

    @Test void shouldSerializeRevocationBeforeClaimWithObservedDatabaseLockWait() throws Exception { orderedRace(true); }
    @Test void shouldPreserveClaimBeforeRevocationWithObservedDatabaseLockWait() throws Exception { orderedRace(false); }
    private void orderedRace(boolean revokeFirst) throws Exception {
        long plan = plan(); grant(plan, 2, true, false); grant(plan, 3, true, false);
        var request = publish(roster.createManaged(shift(plan, 2, false), 1, "test"));
        var claim = operation(); var revoke = command(3, 0, false, false, false); long before = audits();
        var holding = new CountDownLatch(1); var release = new CountDownLatch(1); var contenderEntered = new CountDownLatch(1);
        String action = revokeFirst ? "ONCALL_MEMBER_CHANGED" : "ONCALL_OPEN_HANDOFF_CLAIMED";
        Object target = revokeFirst ? plan : request.id();
        doAnswer(call -> {
            call.callRealMethod(); holding.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); return null;
        }).when(audit).recordAs(eq(revokeFirst ? 1L : 3L), eq("test"), eq(action), anyString(), eq(target), anyString());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var owner = executor.submit(() -> revokeFirst ? members.change(plan, revoke, 1, "test") : open.claim(request.id(), claim, 3, "test"));
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            var contender = executor.submit(() -> {
                contenderEntered.countDown();
                try { return revokeFirst ? open.claim(request.id(), claim, 3, "test") : members.change(plan, revoke, 1, "test"); }
                catch (ApiException error) { return error; }
            });
            assertThat(contenderEntered.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int observed;
            do { observed = waitingForDatabaseLock(); if (observed > 0) break; LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5)); }
            while (System.nanoTime() < deadline);
            assertThat(observed).as("actual SQL lock wait before releasing the transaction, not timing-based ordering").isPositive();
            assertThat(contender.isDone()).isFalse();
            release.countDown(); Object first = owner.get(15, TimeUnit.SECONDS), second = contender.get(15, TimeUnit.SECONDS);
            if (revokeFirst) {
                assertThat(first).isInstanceOf(OnCallPlanMembershipService.ChangeResult.class);
                assertThat(second).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ONCALL_PLAN_RESPONSE_FORBIDDEN"));
                assertThat(open.coverage(request.id()).replacement()).isNull(); assertThat(open.coverage(request.id()).operation()).isNull();
                assertThat(open.get(request.id()).status()).isEqualTo("OPEN"); assertThat(audits()).isEqualTo(before + 1);
            } else {
                assertThat(second).isInstanceOf(OnCallPlanMembershipService.ChangeResult.class);
                var receipt = (OnCallOpenHandoffService.CoverageView) first;
                assertThat(open.coverage(request.id()).replacement().cancelledAt()).isNull();
                assertThat(open.claim(request.id(), claim, 3, "test").operation()).isEqualTo(receipt.operation());
                assertThat(open.get(request.id()).status()).isEqualTo("CLAIMED"); assertThat(audits()).isEqualTo(before + 3);
            }
            System.out.println("PLAN_MEMBERSHIP_LOCK_ORDER " + json.writeValueAsString(Map.of("case", revokeFirst
                    ? "shouldSerializeRevocationBeforeClaimWithObservedDatabaseLockWait" : "shouldPreserveClaimBeforeRevocationWithObservedDatabaseLockWait",
                    "revocationFirst", revokeFirst, "databaseLockWaitObserved", true, "responsibilityMatchesCommitOrder", true)));
        } finally { release.countDown(); executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    protected int waitingForDatabaseLock() throws Exception {
        return jdbc.sql("SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE BLOCKER_ID IS NOT NULL").query(Integer.class).single();
    }

    @Test void shouldFilterDepartedRequestersBeforeAvailableLimitAndAllowOwnWithdrawal() {
        long plan = plan(); grant(plan, 2, true, false); grant(plan, 3, true, false);
        var source = roster.createManaged(shift(plan, 2, false), 1, "test"); var request = publish(source);
        assertThat(open.list(plan, 3, "AVAILABLE", null).requests()).extracting(OnCallOpenHandoffService.View::id).contains(request.id());
        long unrelated = plan(); grant(unrelated, 2, true, false); grant(unrelated, 3, true, false);
        var otherSource = roster.createManaged(shift(unrelated, 2, false), 1, "test");
        revoke(unrelated, 2);
        for (int i = 0; i < 205; i++) {
            jdbc.sql("""
                    INSERT INTO oncall_open_handoff(schedule_id,source_shift_id,source_version,requester_id,request_key,starts_at,ends_at,reason)
                    VALUES (:plan,:source,0,2,:key,:start,:end,'newer but requester has left plan')
                    """).param("plan", unrelated).param("source", otherSource.id()).param("key", key())
                    .param("start", otherSource.startsAt()).param("end", otherSource.endsAt()).update();
        }
        var filtered = open.list(null, 3, "AVAILABLE", null);
        assertThat(filtered.requests()).extracting(OnCallOpenHandoffService.View::id).contains(request.id());
        assertThat(filtered.requests()).noneMatch(row -> row.scheduleId() == unrelated);
        assertThat(filtered.truncated()).isFalse();
        revoke(plan, 2); long before = audits();
        assertThat(open.list(plan, 3, "AVAILABLE", null).requests()).isEmpty();
        denied(() -> open.claim(request.id(), operation(), 3, "test"), "ONCALL_PLAN_RESPONSE_FORBIDDEN");
        assertThat(audits()).isEqualTo(before);
        assertThat(open.withdraw(request.id(), operation(), 2, "test").request().status()).isEqualTo("WITHDRAWN");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM oncall_shift WHERE id=:id AND cancelled_at IS NULL").param("id", source.id()).query(Long.class).single()).isEqualTo(1);
    }

    @Test void shouldRequireResponseForManualOverridesAndManagementForCancellation() {
        long plan = plan(); grant(plan, 2, true, false);
        for (boolean override : List.of(false, true)) denied(() -> roster.createManaged(shift(plan, 3, override), 1, "test"), "ONCALL_PLAN_RESPONSE_FORBIDDEN");
        var source = roster.createManaged(shift(plan, 2, false), 1, "test");
        denied(() -> roster.cancelManaged(source.id(), 0, "no grant", 3, "test"), "ONCALL_PLAN_MANAGEMENT_FORBIDDEN");
        grant(plan, 3, false, true);
        assertThat(roster.cancelManaged(source.id(), 0, "explicit manager", 3, "test").cancelledAt()).isNotNull();
    }

    @Test void shouldRejectNewDesignatedAcceptanceButAllowDepartedTargetRejection() {
        long plan = plan(); grant(plan, 2, true, false); grant(plan, 3, true, false);
        var source = roster.createManaged(shift(plan, 2, false), 1, "test");
        var request = handoffs.request(new OnCallHandoffService.Command(source.id(), 0, 3, key(), source.startsAt(), source.endsAt(), "designated"), 2, "test");
        revoke(plan, 3); long before = audits();
        denied(() -> handoffs.decide(request.id(), new OnCallHandoffService.Decision(0, "ACCEPTED", "accept"), 3, "test"), "ONCALL_PLAN_RESPONSE_FORBIDDEN");
        assertThat(audits()).isEqualTo(before);
        assertThat(handoffs.decide(request.id(), new OnCallHandoffService.Decision(0, "REJECTED", "decline"), 3, "test").status()).isEqualTo("REJECTED");
    }

    @Test void shouldRequireBothParticipantsInBothPlansForBilateralSwap() {
        long a = plan(), b = plan(); grant(a, 2, true, false); grant(b, 3, true, false);
        var first = roster.createManaged(shift(a, 2, false), 1, "test"); var second = roster.createManaged(shift(b, 3, false), 1, "test");
        var command = new OnCallSwapService.Command(first.id(), 0, second.id(), 0, key(), "cross-plan");
        denied(() -> swaps.request(command, 2, "test"), "ONCALL_PLAN_RESPONSE_FORBIDDEN");
        grant(a, 3, true, false); grant(b, 2, true, false);
        var request = swaps.request(command, 2, "test"); revoke(b, 2); long before = audits();
        denied(() -> swaps.decide(request.id(), new OnCallSwapService.Decision(0, "ACCEPTED", "accept"), 3, "test"), "ONCALL_PLAN_RESPONSE_FORBIDDEN");
        assertThat(audits()).isEqualTo(before);
        assertThat(swaps.decide(request.id(), new OnCallSwapService.Decision(0, "WITHDRAWN", "withdraw"), 2, "test").status()).isEqualTo("WITHDRAWN");
    }

    @Test void shouldBlockOriginalRotationSlotsWithoutReassigningHistoryAndResumeOnlyMissingSlots() {
        long plan = plan(); grant(plan, 2, true, false); grant(plan, 3, true, false);
        var at = now().truncatedTo(ChronoUnit.MINUTES).plusDays(20);
        var rotation = rotations.create(new OnCallRotationService.Command(plan, "membership order", at, 1440, List.of(2L, 3L)), 1L, "test");
        assertThat(rotations.slots(rotation.id(), at, at.plusDays(14)).slots()).isEmpty();
        revoke(plan, 2); rotations.scan(at, null, "test");
        var blocked = rotations.slots(rotation.id(), at, at.plusDays(14)).slots();
        assertThat(blocked.get(0).userId()).isEqualTo(2); assertThat(blocked.get(0).status()).isEqualTo("MEMBER_UNAVAILABLE");
        assertThat(blocked.get(1).userId()).isEqualTo(3); assertThat(blocked.get(1).status()).isEqualTo("GENERATED");
        long oldShift = blocked.get(1).shiftId(); roster.cancel(oldShift, 0, "keep cancellation", 1L, "test");
        grant(plan, 2, true, false); rotations.scan(at, null, "test");
        var restored = rotations.slots(rotation.id(), at, at.plusDays(14)).slots();
        assertThat(restored.get(0).status()).isEqualTo("GENERATED");
        assertThat(restored.get(1).shiftId()).isEqualTo(oldShift); assertThat(restored.get(1).cancelledAt()).isNotNull();
        revoke(plan, 2);
        var retained = rotations.slots(rotation.id(), at, at.plusDays(14)).slots().get(0);
        assertThat(retained.status()).isEqualTo("GENERATED"); assertThat(retained.shiftId()).isEqualTo(restored.get(0).shiftId()); assertThat(retained.memberAvailable()).isFalse();
    }

    @Test void shouldNotWriteRotationWarningWhenManualScannerHasNoPlanManagement() {
        long plan = plan(); grant(plan, 2, true, false);
        var at = now().truncatedTo(ChronoUnit.MINUTES).plusDays(20);
        var rotation = rotations.create(new OnCallRotationService.Command(plan, "unauthorized scan", at, 1440, List.of(2L)), 1L, "test");
        var before = rotations.get(rotation.id()); long auditBefore = audits();
        assertThat(rotations.scan(at, 3L, "test").failedRotations()).contains(rotation.id());
        assertThat(rotations.get(rotation.id())).isEqualTo(before);
        assertThat(rotations.slots(rotation.id(), at, at.plusDays(14)).slots()).isEmpty(); assertThat(audits()).isEqualTo(auditBefore);
    }

    @Test void shouldRetainBadRotationTargetErrorContractAndRejectInvalidManagementTargets() {
        long plan = plan();
        denied(() -> rotations.create(new OnCallRotationService.Command(plan, "missing target", now().truncatedTo(ChronoUnit.MINUTES), 1440, List.of(Long.MAX_VALUE)), 1L, "test"), "ONCALL_ROTATION_INVALID");
        denied(() -> members.change(plan, command(2, null, true, false, true), 1, "test"), "ONCALL_MEMBER_INVALID");
        denied(() -> members.change(plan, command(4, null, true, true, false), 1, "test"), "ONCALL_MEMBER_INVALID");
        assertThat(members.list(plan)).isEmpty();
    }

    @Test void shouldRecheckCurrentRoleAndNotResurrectRevokedMembershipWhenAccountIsRestored() throws Exception {
        long plan = plan(); grant(plan, 3, true, true); String token = login("lina");
        try {
            jdbc.sql("UPDATE sys_user SET role_code='ON_CALL' WHERE id=3").update();
            mvc.perform(post(base(plan)).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(command(2, null, true, true, false)))).andExpect(status().isForbidden());
        } finally { jdbc.sql("UPDATE sys_user SET role_code='OPS_MANAGER' WHERE id=3").update(); }
        revoke(plan, 3);
        try { jdbc.sql("UPDATE sys_user SET status='DISABLED' WHERE id=3").update(); }
        finally { jdbc.sql("UPDATE sys_user SET status='ACTIVE' WHERE id=3").update(); }
        denied(() -> roster.createManaged(shift(plan, 3, false), 1, "test"), "ONCALL_PLAN_RESPONSE_FORBIDDEN");
        assertThat(members.list(plan)).singleElement().satisfies(m -> assertThat(m.active()).isFalse());
    }

    private long plan() { return insert(jdbc.sql("INSERT INTO oncall_schedule(service_resource_id,name) VALUES (3,:name)").param("name", "membership-" + key())); }
    private long user() { return insert(jdbc.sql("INSERT INTO sys_user(username,password_hash,display_name,role_code) SELECT :name,password_hash,'membership user','ON_CALL' FROM sys_user WHERE id=2").param("name", "member-" + key())); }
    private long insert(JdbcClient.StatementSpec statement) { var key = new GeneratedKeyHolder(); statement.update(key, "id"); return key.getKey().longValue(); }
    private OnCallPlanMembershipService.ChangeResult grant(long plan, long user, boolean respond, boolean manage) {
        Integer version = members.list(plan).stream().filter(m -> m.userId() == user).map(OnCallPlanMembershipService.MemberView::version).findFirst().orElse(null);
        return members.change(plan, command(user, version, true, respond, manage), 1, "test");
    }
    private void revoke(long plan, long user) { int version = members.list(plan).stream().filter(m -> m.userId() == user).findFirst().orElseThrow().version(); members.change(plan, command(user, version, false, false, false), 1, "test"); }
    private OnCallPlanMembershipService.Command command(long user, Integer version, boolean active, boolean respond, boolean manage) { return new OnCallPlanMembershipService.Command(user, version, active, respond, manage, key(), "captured membership command"); }
    private OnCallRosterService.ShiftCommand shift(long plan, long user, boolean override) { var at = now().truncatedTo(ChronoUnit.SECONDS).plusDays(1); return new OnCallRosterService.ShiftCommand(plan, user, at, at.plusHours(4), override, "membership shift"); }
    private OnCallOpenHandoffService.View publish(OnCallRosterService.ShiftView source) { return open.request(new OnCallOpenHandoffService.Command(source.id(), 0, key(), source.startsAt(), source.endsAt(), "membership open"), source.userId(), "test"); }
    private OnCallOpenHandoffService.OperationCommand operation() { return new OnCallOpenHandoffService.OperationCommand(0, key(), "captured claim"); }
    private LocalDateTime now() { return jdbc.sql("SELECT CURRENT_TIMESTAMP(6)").query((rs, n) -> rs.getObject(1, LocalDateTime.class)).single(); }
    private long audits() { return jdbc.sql("SELECT COUNT(*) FROM audit_log").query(Long.class).single(); }
    private long operations(long plan) { return jdbc.sql("SELECT COUNT(*) FROM oncall_schedule_member_operation WHERE schedule_id=:plan").param("plan", plan).query(Long.class).single(); }
    private String key() { return UUID.randomUUID().toString(); }
    private String base(long plan) { return "/api/v1/on-call/schedules/" + plan + "/members"; }
    private String login(String name) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username", name, "password", "OpsPilot@2026"))))
                .andExpect(status().isOk()).andReturn().getResponse();
        String token = json.readTree(response.getContentAsString()).path("data").path("accessToken").asText();
        assertThat(token).isNotBlank();
        return "Bearer " + token;
    }
    private void denied(Runnable action, String code) { assertThatThrownBy(action::run).isInstanceOf(ApiException.class).extracting("code").isEqualTo(code); }
}
