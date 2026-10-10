package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
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
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Identical paired-revocation business assertions, not two unrelated cancellation requests. */
abstract class SwapRevocationScenarios {
    @Autowired OnCallSwapService swaps;
    @Autowired OnCallRosterService roster;
    @Autowired OnCallCoverageService coverage;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DataSource datasource;
    @SpyBean AuditService audit;

    @BeforeEach void actualDatabaseIdentity(TestInfo test) throws Exception {
        try(var connection=datasource.getConnection()) {
            var metadata=connection.getMetaData();
            if(getClass().getSimpleName().startsWith("MySql")) {
                assertThat(metadata.getDatabaseProductName()).isEqualTo("MySQL");
                assertThat(metadata.getDatabaseProductVersion()).startsWith("8.4.");
                assertThat(connection.getCatalog()).isEqualTo("opspilot_swap_revocation_test");
            }
            assertThat(jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version='37' AND success=TRUE").query(Long.class).single()).isEqualTo(1);
            System.out.println("CP92_SWAP_REVOCATION_DATABASE "+json.writeValueAsString(Map.of("case",test.getTestMethod().orElseThrow().getName(),
                    "product",metadata.getDatabaseProductName(),"version",metadata.getDatabaseProductVersion(),"schema",connection.getCatalog(),"migration37",true)));
        }
    }
    @AfterEach void resetAudit(){reset(audit);}

    @Test void shouldRevokeBothCoveragesInOneHttpCommandAndKeepAcceptedHistory() throws Exception {
        var f=accepted(true);String body="{\"swapVersion\":1,\"firstReplacementVersion\":0,\"secondReplacementVersion\":0,\"operationKey\":\""+UUID.randomUUID()+"\",\"reason\":\"原子撤销双方覆盖\"}";
        var token=login("admin");var history=notificationHistory(f);
        assertThat(history).hasSize(3); // Nonempty frozen request and decision payloads, not vacuous disabled history.
        mvc.perform(post("/api/v1/on-call/swaps/"+f.swap().id()+"/coverage/revoke").header("Authorization",token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertThat(swaps.get(f.swap().id())).isEqualTo(f.swap());
        assertOwner(f.first(),2);assertOwner(f.second(),3);
        for(long id:new long[]{f.swap().firstReplacementShiftId(),f.swap().secondReplacementShiftId()}){
            assertThat(jdbc.sql("SELECT cancelled_at FROM oncall_shift WHERE id=:id").param("id",id).query(LocalDateTime.class).single()).isNotNull();
        }
        assertThat(revocations(f)).isEqualTo(1);assertThat(pairAudits(f)).isEqualTo(3);
        assertThat(notificationHistory(f)).isEqualTo(history);
        mvc.perform(get("/api/v1/on-call/swaps/"+f.swap().id()+"/coverage").header("Authorization",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accepted.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.firstReplacement.version").value(1)).andExpect(jsonPath("$.data.secondReplacement.version").value(1))
                .andExpect(jsonPath("$.data.revocation.swapVersion").value(1)).andExpect(jsonPath("$.data.databaseNow").exists());
    }
    @Test void shouldRevokeSameSchedulePairAndAcknowledgeOriginalCommandsWithoutReviving() {
        var f=accepted(false);var command=command();long before=audits();var receipt=swaps.revokeCoverage(f.swap().id(),command,1,"test");
        assertRevoked(f,command);assertThat(audits()).isEqualTo(before+3);
        assertThat(swaps.revokeCoverage(f.swap().id(),command,1,"test").revocation()).isEqualTo(receipt.revocation());
        assertThat(swaps.request(f.command(),2,"test")).isEqualTo(f.swap());
        assertThat(swaps.decide(f.swap().id(),new OnCallSwapService.Decision(0,"ACCEPTED","本人明确接受"),3,"test")).isEqualTo(f.swap());
        assertThat(audits()).isEqualTo(before+3);assertOwner(f.first(),2);assertOwner(f.second(),3);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM oncall_shift WHERE schedule_id=:id").param("id",f.first().scheduleId()).query(Long.class).single()).isEqualTo(4);
    }
    @Test void shouldAcknowledgeOriginalKeyAfterEndWithoutNewAuditOrCancellation() {
        var f=accepted(true);var command=command();var receipt=swaps.revokeCoverage(f.swap().id(),command,1,"test");long before=audits();
        shiftTimes(f,true,now().minusHours(2),now().minusHours(1));
        assertThat(swaps.revokeCoverage(f.swap().id(),command,1,"test").revocation()).isEqualTo(receipt.revocation());
        assertThat(audits()).isEqualTo(before);assertThat(pairAudits(f)).isEqualTo(3);assertThat(revocations(f)).isEqualTo(1);
    }
    @Test void shouldRejectChangedOriginalKeyPayloadAndCrossSwapReuseWithoutWrites() {
        var f=accepted(true);var c=command();swaps.revokeCoverage(f.swap().id(),c,1,"test");var other=accepted(true);long before=audits();
        for(var changed:List.of(new OnCallSwapService.RevocationCommand(2,0,0,c.operationKey(),c.reason()),
                new OnCallSwapService.RevocationCommand(1,1,0,c.operationKey(),c.reason()),new OnCallSwapService.RevocationCommand(1,0,1,c.operationKey(),c.reason()),
                new OnCallSwapService.RevocationCommand(1,0,0,c.operationKey(),"不同说明")))assertCode(()->swaps.revokeCoverage(f.swap().id(),changed,1,"test"),"ONCALL_SWAP_REVOCATION_KEY_REUSED");
        assertCode(()->swaps.revokeCoverage(other.swap().id(),c,1,"test"),"ONCALL_SWAP_REVOCATION_KEY_REUSED");
        assertUntouched(other);assertThat(audits()).isEqualTo(before);
    }
    @Test void shouldRejectNewKeyOrOtherManagerAfterRevocation() {
        var f=accepted(true);var c=command();swaps.revokeCoverage(f.swap().id(),c,1,"test");long before=audits();
        assertCode(()->swaps.revokeCoverage(f.swap().id(),command(),1,"test"),"ONCALL_SWAP_COVERAGE_ALREADY_REVOKED");
        assertCode(()->swaps.revokeCoverage(f.swap().id(),c,3,"test"),"ONCALL_SWAP_COVERAGE_ALREADY_REVOKED");
        assertThat(audits()).isEqualTo(before);assertThat(pairAudits(f)).isEqualTo(3);
    }
    @Test void shouldRecheckCurrentManagerQualificationBeforeOriginalAcknowledgement() {
        var f=accepted(true);var c=command();swaps.revokeCoverage(f.swap().id(),c,1,"test");long before=audits();
        try {
            jdbc.sql("UPDATE sys_user SET role_code='ON_CALL' WHERE id=1").update();assertCode(()->swaps.revokeCoverage(f.swap().id(),c,1,"test"),"ONCALL_SWAP_FORBIDDEN");
            jdbc.sql("UPDATE sys_user SET role_code='ADMIN',status='DISABLED' WHERE id=1").update();assertCode(()->swaps.revokeCoverage(f.swap().id(),c,1,"test"),"ONCALL_SWAP_FORBIDDEN");
        } finally {jdbc.sql("UPDATE sys_user SET role_code='ADMIN',status='ACTIVE' WHERE id=1").update();}
        assertThat(audits()).isEqualTo(before);assertThat(swaps.revokeCoverage(f.swap().id(),c,1,"test").revocation()).isNotNull();
    }
    @Test void shouldRejectEitherIndependentCancellationWithoutCancellingCounterpart() {
        for(boolean first:List.of(true,false)) {
            var f=accepted(true);long id=first?f.swap().firstReplacementShiftId():f.swap().secondReplacementShiftId();
            roster.cancel(id,0,"历史独立取消",1L,"test");long before=audits();
            assertCode(()->swaps.revokeCoverage(f.swap().id(),command(),1,"test"),"ONCALL_SHIFT_VERSION_CONFLICT");
            var facts=swaps.coverage(f.swap().id());assertThat(first?facts.secondReplacement().cancelledAt():facts.firstReplacement().cancelledAt()).isNull();
            assertThat(revocations(f)).isZero();assertThat(audits()).isEqualTo(before);assertThat(pairAudits(f)).isEqualTo(1);
        }
    }
    @Test void shouldRejectEachStaleCapturedVersionAndOverflowWithoutWrites() {
        var f=accepted(true);var c=command();long before=audits();
        assertCode(()->swaps.revokeCoverage(f.swap().id(),new OnCallSwapService.RevocationCommand(0,0,0,c.operationKey(),c.reason()),1,"test"),"ONCALL_SWAP_VERSION_CONFLICT");
        for(var stale:List.of(new OnCallSwapService.RevocationCommand(1,1,0,c.operationKey(),c.reason()),new OnCallSwapService.RevocationCommand(1,0,1,c.operationKey(),c.reason())))
            assertCode(()->swaps.revokeCoverage(f.swap().id(),stale,1,"test"),"ONCALL_SHIFT_VERSION_CONFLICT");
        jdbc.sql("UPDATE oncall_shift SET version=2147483647 WHERE id=:id").param("id",f.swap().secondReplacementShiftId()).update();
        assertCode(()->swaps.revokeCoverage(f.swap().id(),new OnCallSwapService.RevocationCommand(1,0,Integer.MAX_VALUE,c.operationKey(),c.reason()),1,"test"),"ONCALL_SHIFT_VERSION_CONFLICT");
        assertThat(revocations(f)).isZero();assertThat(pairAudits(f)).isZero();assertThat(audits()).isEqualTo(before);
    }
    @Test void shouldRejectNonAcceptedStatusesMissingRequestAndMalformedCommand() {
        for(String state:List.of("PENDING","REJECTED","WITHDRAWN")) {
            var f=accepted(true);jdbc.sql("UPDATE oncall_shift_swap SET status=:state WHERE id=:id").param("state",state).param("id",f.swap().id()).update();long before=audits();
            assertCode(()->swaps.revokeCoverage(f.swap().id(),command(),1,"test"),"ONCALL_SWAP_VERSION_CONFLICT");assertUntouched(f);assertThat(audits()).isEqualTo(before);
        }
        assertThatThrownBy(()->swaps.coverage(Long.MAX_VALUE)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status().value()).isEqualTo(404));
        var f=accepted(true);long before=audits();
        for(String key:List.of("not-a-key",UUID.randomUUID().toString().toUpperCase()))
            assertThatThrownBy(()->swaps.revokeCoverage(f.swap().id(),new OnCallSwapService.RevocationCommand(1,0,0,key,"说明"),1,"test")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status().value()).isEqualTo(400));
        for(String reason:List.of(" ","x".repeat(501)))assertThatThrownBy(()->swaps.revokeCoverage(f.swap().id(),new OnCallSwapService.RevocationCommand(1,0,0,UUID.randomUUID().toString(),reason),1,"test")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status().value()).isEqualTo(400));
        assertUntouched(f);assertThat(audits()).isEqualTo(before);
    }
    @Test void shouldRejectChangedReplacementSnapshotOrOrdinaryFlagWithoutHalfCancellation() {
        for(String change:List.of("user_id=1","schedule_id=1","starts_at=DATEADD('SECOND',1,starts_at)","override_flag=FALSE")) {
            var f=accepted(true);String sql=change.startsWith("starts")?"UPDATE oncall_shift SET starts_at=:start WHERE id=:id":"UPDATE oncall_shift SET "+change+" WHERE id=:id";
            var statement=jdbc.sql(sql).param("id",f.swap().secondReplacementShiftId());if(change.startsWith("starts"))statement.param("start",f.second().startsAt().plusSeconds(1));statement.update();
            long before=audits();assertCode(()->swaps.revokeCoverage(f.swap().id(),command(),1,"test"),"ONCALL_SWAP_SOURCE_CHANGED");assertUntouched(f);assertThat(audits()).isEqualTo(before);
        }
    }
    @Test void shouldRejectEitherEndedCoverageButAllowAlreadyStartedRemainingPair() {
        for(boolean first:List.of(true,false)) {var f=accepted(true);shiftTimes(f,first,now().minusHours(2),now().minusHours(1));long before=audits();
            assertCode(()->swaps.revokeCoverage(f.swap().id(),command(),1,"test"),"ONCALL_SWAP_COVERAGE_EXPIRED");assertUntouched(f);assertThat(audits()).isEqualTo(before);}
        var f=accepted(true);shiftTimes(f,true,now().minusHours(1),now().plusHours(1));long before=audits();var c=command();
        swaps.revokeCoverage(f.swap().id(),c,1,"test");assertRevoked(f,c);assertThat(audits()).isEqualTo(before+3);
    }
    @Test void shouldAllowCleanupOfInactiveSchedulesWithoutPromisingRestoredCoverage() {
        var f=accepted(true);roster.cancel(f.first().id(),0,"原普通班已取消",1L,"test");
        jdbc.sql("UPDATE oncall_schedule SET active=FALSE WHERE id IN (:first,:second)").param("first",f.first().scheduleId()).param("second",f.second().scheduleId()).update();
        var c=command();swaps.revokeCoverage(f.swap().id(),c,3,"test");assertRevoked(f,c);
        assertThat(coverage.coverage(f.first().scheduleId(),f.first().startsAt(),f.first().endsAt()).segments()).allSatisfy(s->assertThat(s.userId()).isNull());
    }
    @Test void shouldRollbackBothCancellationsWhenSecondCancellationAuditFails() {
        var f=accepted(true);long before=audits();doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("second cancellation audit fault");})
                .when(audit).recordAs(eq(1L),eq("test"),eq("ONCALL_SHIFT_CANCELLED"),eq("ONCALL_SHIFT"),eq(f.swap().secondReplacementShiftId()),anyString());
        assertThatThrownBy(()->swaps.revokeCoverage(f.swap().id(),command(),1,"test")).isInstanceOf(IllegalStateException.class);
        assertUntouched(f);assertThat(audits()).isEqualTo(before);assertOwner(f.first(),3);assertOwner(f.second(),2);
    }
    @Test void shouldRollbackBothCancellationsRevocationAndAuditsWhenFinalAuditFails() {
        var f=accepted(true);long before=audits();doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("final revocation audit fault");})
                .when(audit).recordAs(eq(1L),eq("test"),eq("ONCALL_SWAP_COVERAGE_REVOKED"),eq("ONCALL_SWAP"),eq(f.swap().id()),anyString());
        assertThatThrownBy(()->swaps.revokeCoverage(f.swap().id(),command(),1,"test")).isInstanceOf(IllegalStateException.class);
        assertUntouched(f);assertThat(audits()).isEqualTo(before);assertOwner(f.first(),3);assertOwner(f.second(),2);
    }
    @Test void shouldSerializeConcurrentOriginalKeyAcknowledgementsAndDifferentKeyRace() throws Exception {
        var f=accepted(true);var c=command();long before=audits();var same=race(()->swaps.revokeCoverage(f.swap().id(),c,1,"test"),()->swaps.revokeCoverage(f.swap().id(),c,1,"test"));
        assertThat(same).allSatisfy(result->assertThat(result).isInstanceOf(OnCallSwapService.CoverageView.class));assertRevoked(f,c);assertThat(audits()).isEqualTo(before+3);
        var other=accepted(true);long otherBefore=audits();var different=race(()->swaps.revokeCoverage(other.swap().id(),command(),1,"test"),()->swaps.revokeCoverage(other.swap().id(),command(),3,"test"));
        assertThat(different.stream().filter(x->x instanceof OnCallSwapService.CoverageView).count()).isEqualTo(1);
        assertThat(different.stream().filter(x->x instanceof ApiException e&&e.code().equals("ONCALL_SWAP_COVERAGE_ALREADY_REVOKED")).count()).isEqualTo(1);
        assertThat(pairAudits(other)).isEqualTo(3);assertThat(revocations(other)).isEqualTo(1);assertThat(audits()).isEqualTo(otherBefore+3);
    }
    @Test void shouldSerializeLegacySingleCancellationAgainstAtomicPairWithoutHalfCommit() throws Exception {
        var f=accepted(true);long before=audits();var results=race(()->swaps.revokeCoverage(f.swap().id(),command(),1,"test"),()->roster.cancel(f.swap().firstReplacementShiftId(),0,"独立竞争取消",1L,"test"));
        var facts=swaps.coverage(f.swap().id());assertThat(facts.firstReplacement().cancelledAt()).isNotNull();
        if(facts.revocation()!=null) {assertThat(facts.secondReplacement().cancelledAt()).isNotNull();assertThat(pairAudits(f)).isEqualTo(3);assertThat(audits()).isEqualTo(before+3);}
        else {assertThat(facts.secondReplacement().cancelledAt()).isNull();assertThat(pairAudits(f)).isEqualTo(1);assertThat(audits()).isEqualTo(before+1);}
        assertThat(results.stream().filter(x->x instanceof ApiException e&&e.code().equals("ONCALL_SHIFT_VERSION_CONFLICT")).count()).isEqualTo(1);
    }
    @Test void shouldEnforceHttpAuthenticationRolesAndAllThreeExplicitVersions() throws Exception {
        var f=accepted(true);var body=json.valueToTree(command());String admin=login("admin"),oncall=login("zhangwei"),auditor=login("auditor");long before=audits();String url="/api/v1/on-call/swaps/"+f.swap().id()+"/coverage/revoke";
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isUnauthorized());
        for(String token:List.of(oncall,auditor))mvc.perform(post(url).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isForbidden());
        for(String field:List.of("swapVersion","firstReplacementVersion","secondReplacementVersion")) {
            for(int variant=0;variant<3;variant++){var bad=(com.fasterxml.jackson.databind.node.ObjectNode)body.deepCopy();if(variant==0)bad.remove(field);else if(variant==1)bad.putNull(field);else bad.put(field,-1);
                mvc.perform(post(url).header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content(bad.toString())).andExpect(status().isBadRequest());}
        }
        assertUntouched(f);assertThat(audits()).isEqualTo(before);
    }
    @Test void shouldRollbackAfterFinalAuditCrossesActualDatabaseEnd() {
        var f=accepted(true);var requestedEnd=now().withNano(900_000_000).plusSeconds(3);
        shiftTimes(f,true,requestedEnd.minusHours(1),requestedEnd);var persistedEnd=persistedDeadline(f);long before=audits();
        doAnswer(invocation->{invocation.callRealMethod();awaitPersistedEnd("shouldRollbackAfterFinalAuditCrossesActualDatabaseEnd",requestedEnd,persistedEnd);return null;}).when(audit)
                .recordAs(eq(1L),eq("test"),eq("ONCALL_SWAP_COVERAGE_REVOKED"),eq("ONCALL_SWAP"),eq(f.swap().id()),anyString());
        assertCode(()->swaps.revokeCoverage(f.swap().id(),command(),1,"test"),"ONCALL_SWAP_COVERAGE_EXPIRED");assertUntouched(f);assertThat(audits()).isEqualTo(before);
    }
    @Test void shouldRecheckActualDatabaseEndAfterWaitingForScheduleLock() throws Exception {
        var f=accepted(true);var requestedEnd=now().withNano(900_000_000).plusSeconds(3);
        shiftTimes(f,true,requestedEnd.minusHours(1),requestedEnd);var persistedEnd=persistedDeadline(f);long before=audits();var executor=Executors.newSingleThreadExecutor();
        try(var connection=datasource.getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT id FROM oncall_schedule WHERE id=? FOR UPDATE")) {statement.setLong(1,Math.min(f.first().scheduleId(),f.second().scheduleId()));try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();}}
            var entered=new CountDownLatch(1);var waiting=executor.submit(()->{entered.countDown();return outcome(new CountDownLatch(0),()->swaps.revokeCoverage(f.swap().id(),command(),1,"test"));});
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));assertThat(waiting.isDone()).isFalse();
            awaitPersistedEnd("shouldRecheckActualDatabaseEndAfterWaitingForScheduleLock",requestedEnd,persistedEnd);connection.commit();
            assertThat(waiting.get(15,TimeUnit.SECONDS)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ONCALL_SWAP_COVERAGE_EXPIRED"));
        } finally {executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
        assertUntouched(f);assertThat(audits()).isEqualTo(before);
    }
    private void awaitDatabaseEnd(LocalDateTime end) {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(now().isBefore(end)&&System.nanoTime()<deadline)LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));assertThat(now()).isAfterOrEqualTo(end);}
    private LocalDateTime persistedDeadline(Fixture f) {
        var facts=swaps.coverage(f.swap().id());var end=facts.firstReplacement().endsAt();
        assertThat(facts.accepted().firstEndsAt()).isEqualTo(end);
        assertThat(jdbc.sql("SELECT ends_at FROM oncall_shift WHERE id=:id").param("id",f.first().id()).query(LocalDateTime.class).single()).isEqualTo(end);
        if(getClass().getSimpleName().startsWith("MySql"))assertThat(end.getNano()).isZero();
        return end;
    }
    private void awaitPersistedEnd(String caseName,LocalDateTime requested,LocalDateTime persisted)throws Exception {
        awaitDatabaseEnd(persisted);var released=now();assertThat(released).isAfterOrEqualTo(persisted);
        System.out.println("CP93_SWAP_REVOCATION_TIME_BARRIER "+json.writeValueAsString(Map.of("case",caseName,
                "requestedEnd",requested.toString(),"persistedEnd",persisted.toString(),"releasedDatabaseNow",released.toString(),"releasedAfterPersistedEnd",true)));
    }
    private OnCallSwapService.RevocationCommand command(){return new OnCallSwapService.RevocationCommand(1,0,0,UUID.randomUUID().toString()," 原子撤销中文/emoji🙂 ");}
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,String code){assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo(code));}
    private long audits(){return jdbc.sql("SELECT COUNT(*) FROM audit_log").query(Long.class).single();}
    private long revocations(Fixture f){return jdbc.sql("SELECT COUNT(*) FROM oncall_swap_revocation WHERE swap_id=:id").param("id",f.swap().id()).query(Long.class).single();}
    private long pairAudits(Fixture f){return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE (action='ONCALL_SWAP_COVERAGE_REVOKED' AND target_type='ONCALL_SWAP' AND target_id=:swap) OR (action='ONCALL_SHIFT_CANCELLED' AND target_type='ONCALL_SHIFT' AND target_id IN (:first,:second))")
            .param("swap",Long.toString(f.swap().id())).param("first",Long.toString(f.swap().firstReplacementShiftId())).param("second",Long.toString(f.swap().secondReplacementShiftId())).query(Long.class).single();}
    private List<Map<String,Object>> notificationHistory(Fixture f){return jdbc.sql("SELECT * FROM oncall_swap_notification WHERE swap_id=:id ORDER BY id").param("id",f.swap().id()).query().listOfRows();}
    private void assertUntouched(Fixture f){var facts=swaps.coverage(f.swap().id());assertThat(facts.firstReplacement().cancelledAt()).isNull();assertThat(facts.secondReplacement().cancelledAt()).isNull();assertThat(revocations(f)).isZero();assertThat(pairAudits(f)).isZero();}
    private void assertRevoked(Fixture f,OnCallSwapService.RevocationCommand command){var facts=swaps.coverage(f.swap().id());assertThat(facts.accepted()).isEqualTo(swaps.get(f.swap().id()));
        assertThat(facts.firstReplacement().cancelledAt()).isNotNull();assertThat(facts.secondReplacement().cancelledAt()).isNotNull();assertThat(facts.firstReplacement().version()).isEqualTo(1);assertThat(facts.secondReplacement().version()).isEqualTo(1);
        assertThat(facts.revocation().operationKey()).isEqualTo(command.operationKey());assertThat(facts.revocation().reason()).isEqualTo(command.reason().strip());assertThat(facts.revocation().revokedAt().getNano()%1000).isZero();assertThat(pairAudits(f)).isEqualTo(3);assertThat(revocations(f)).isEqualTo(1);}
    private void shiftTimes(Fixture f,boolean first,LocalDateTime start,LocalDateTime end){String prefix=first?"first":"second";long replacement=first?f.swap().firstReplacementShiftId():f.swap().secondReplacementShiftId();long original=first?f.first().id():f.second().id();
        jdbc.sql("UPDATE oncall_shift SET starts_at=:start,ends_at=:end WHERE id IN (:a,:b)").param("start",start).param("end",end).param("a",original).param("b",replacement).update();
        jdbc.sql("UPDATE oncall_shift_swap SET "+prefix+"_starts_at=:start,"+prefix+"_ends_at=:end WHERE id=:id").param("start",start).param("end",end).param("id",f.swap().id()).update();}
    private List<Object> race(Callable<?> first,Callable<?> second)throws Exception {var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {var a=executor.submit(()->outcome(start,first));var b=executor.submit(()->outcome(start,second));start.countDown();return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));}
        finally {executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}}
    private Object outcome(CountDownLatch start,Callable<?> action)throws Exception {start.await();try{return action.call();}catch(ApiException e){return e;}}
    Fixture accepted(boolean separate) {
        long firstSchedule=schedule(),secondSchedule=separate?schedule():firstSchedule;var start=now().truncatedTo(ChronoUnit.SECONDS).plusDays(1);
        var first=roster.create(new OnCallRosterService.ShiftCommand(firstSchedule,2,start,start.plusHours(4),false,"原第一段"),1L,"test");
        var second=roster.create(new OnCallRosterService.ShiftCommand(secondSchedule,3,start.plusDays(1),start.plusDays(1).plusHours(4),false,"原第二段"),1L,"test");
        var command=new OnCallSwapService.Command(first.id(),0,second.id(),0,UUID.randomUUID().toString(),"双方原接受");
        var pending=swaps.request(command,2,"test");var accepted=swaps.decide(pending.id(),new OnCallSwapService.Decision(0,"ACCEPTED","本人明确接受"),3,"test");
        return new Fixture(first,second,accepted,command);
    }
    private long schedule(){long resource=insert(jdbc.sql("INSERT INTO cmdb_resource(resource_code,resource_type,name,environment,status) VALUES (:code,'APPLICATION','撤销独立服务','TEST','RUNNING')").param("code","REVOKE-"+UUID.randomUUID()));long schedule=insert(jdbc.sql("INSERT INTO oncall_schedule(service_resource_id,name) VALUES (:id,'双覆盖计划')").param("id",resource));PlanMembershipFixtures.grant(jdbc,schedule,1,2,3);return schedule;}
    private long insert(JdbcClient.StatementSpec statement){var key=new GeneratedKeyHolder();statement.update(key,"id");return key.getKey().longValue();}
    void assertOwner(OnCallRosterService.ShiftView source,long user){assertThat(coverage.coverage(source.scheduleId(),source.startsAt(),source.endsAt()).segments()).isNotEmpty().allSatisfy(s->assertThat(s.userId()).isEqualTo(user));}
    LocalDateTime now(){return jdbc.sql("SELECT CURRENT_TIMESTAMP(6)").query((rs,n)->rs.getObject(1,LocalDateTime.class)).single();}
    String login(String username)throws Exception{String response=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\""+username+"\",\"password\":\"OpsPilot@2026\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();return "Bearer "+json.readTree(response).path("data").path("accessToken").asText();}
    record Fixture(OnCallRosterService.ShiftView first,OnCallRosterService.ShiftView second,OnCallSwapService.View swap,OnCallSwapService.Command command){}
}
