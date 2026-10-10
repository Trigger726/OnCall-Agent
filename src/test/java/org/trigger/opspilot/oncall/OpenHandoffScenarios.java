package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Same consent, locking, rollback and HTTP cases execute on H2 and owned MySQL. */
abstract class OpenHandoffScenarios {
    @Autowired OnCallOpenHandoffService open;
    @Autowired OnCallRosterService roster;
    @Autowired OnCallCoverageService coverage;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource datasource;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @SpyBean AuditService audit;
    private static final String BASE="/api/v1/on-call/open-handoffs";

    @BeforeEach void databaseIdentity(TestInfo test) throws Exception {
        try(var connection=datasource.getConnection()) {
            var metadata=connection.getMetaData();
            if(getClass().getSimpleName().startsWith("MySql")) {
                assertThat(metadata.getDatabaseProductName()).isEqualTo("MySQL");
                assertThat(metadata.getDatabaseProductVersion()).startsWith("8.4.");
                assertThat(connection.getCatalog()).isEqualTo("opspilot_open_handoff_test");
            }
            assertThat(jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version='38' AND success=TRUE").query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version='39' AND success=TRUE").query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version='40' AND success=TRUE").query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success=TRUE").query(Long.class).single()).isEqualTo(41);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version='41' AND success=TRUE").query(Long.class).single()).isEqualTo(1);
            System.out.println("CP97_OPEN_HANDOFF_DATABASE "+json.writeValueAsString(Map.of("case",test.getTestMethod().orElseThrow().getName(),
                    "product",metadata.getDatabaseProductName(),"version",metadata.getDatabaseProductVersion(),"schema",connection.getCatalog(),"migration38",true,
                    "migration39",true,"migration40",true,"migration41",true,"versionedMigrations",41)));
        }
    }
    @AfterEach void resetAudit(){reset(audit);}

    @Test void shouldPublishAndClaimThroughHttpWithActualIndependentCoverage() throws Exception {
        var f=fixture(false);var publish=command(f);var token=login("zhangwei");
        var response=mvc.perform(post(BASE).header("Authorization",token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(publish)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("OPEN")).andReturn().getResponse();
        long id=json.readTree(response.getContentAsString()).path("data").path("id").asLong();var operation=operation();String body=json.writeValueAsString(operation);long before=audits();
        mvc.perform(post(BASE+"/"+id+"/claims").header("Authorization",login("lina")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.request.status").value("CLAIMED"))
                .andExpect(jsonPath("$.data.request.claimedBy").value(3)).andExpect(jsonPath("$.data.replacement.userId").value(3));
        var receipt=open.coverage(id);assertThat(receipt.operation().capturedVersion()).isZero();assertThat(receipt.request().version()).isEqualTo(1);
        assertThat(open.request(publish,2,"test")).isEqualTo(receipt.request());assertThat(open.claim(id,operation,3,"test").operation()).isEqualTo(receipt.operation());
        assertThat(audits()).isEqualTo(before+2);assertThat(shifts(f)).isEqualTo(2);assertThat(operations(id)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT version FROM oncall_shift WHERE id=:id").param("id",f.source().id()).query(Integer.class).single()).isZero();
        assertThat(coverage.coverage(f.schedule(),publish.startsAt(),publish.endsAt()).segments()).allSatisfy(s->assertThat(s.userId()).isEqualTo(3L));
        mvc.perform(get(BASE+"/"+id+"/coverage").header("Authorization",login("auditor"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.databaseNow").exists());
    }
    @Test void shouldSerializeConcurrentPublicationOriginalKeys() throws Exception {
        var f=fixture(false);var c=command(f);long before=audits();var results=race(()->open.request(c,2,"test"),()->open.request(c,2,"test"));
        assertThat(results).allSatisfy(r->assertThat(r).isInstanceOf(OnCallOpenHandoffService.View.class));assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(open.list(f.schedule(),2,"ALL",null).requests()).hasSize(1);assertThat(audits()).isEqualTo(before+1);assertThat(shifts(f)).isEqualTo(1);
    }
    @Test void shouldRejectChangedPublicationKeyContentAndMalformedWindows() {
        var f=fixture(false);var c=command(f);open.request(c,2,"test");var other=fixture(false);
        code(()->open.request(new OnCallOpenHandoffService.Command(other.source().id(),0,c.requestKey(),other.source().startsAt(),other.source().endsAt(),c.reason()),2,"test"),"ONCALL_OPEN_HANDOFF_KEY_REUSED");
        code(()->open.request(new OnCallOpenHandoffService.Command(c.sourceShiftId(),0,c.requestKey(),c.startsAt(),c.endsAt(),"changed"),2,"test"),"ONCALL_OPEN_HANDOFF_KEY_REUSED");
        for(var bad:List.of(new OnCallOpenHandoffService.Command(c.sourceShiftId(),0,"bad",c.startsAt(),c.endsAt(),"x"),
                new OnCallOpenHandoffService.Command(c.sourceShiftId(),0,UUID.randomUUID().toString(),c.startsAt().minusSeconds(1),c.endsAt(),"x"),
                new OnCallOpenHandoffService.Command(c.sourceShiftId(),0,UUID.randomUUID().toString(),c.startsAt().plusNanos(1),c.endsAt(),"x")))code(()->open.request(bad,2,"test"),"ONCALL_OPEN_HANDOFF_INVALID");
        code(()->open.request(new OnCallOpenHandoffService.Command(c.sourceShiftId(),1,UUID.randomUUID().toString(),c.startsAt(),c.endsAt(),"x"),2,"test"),"ONCALL_OPEN_HANDOFF_SOURCE_CHANGED");
        assertThat(open.list(f.schedule(),2,"ALL",null).requests()).hasSize(1);
    }
    @Test void shouldAcknowledgeOriginalClaimAfterCancellationAndEndWithoutRevival() {
        var f=fixture(false);var row=publish(f);var c=operation();var accepted=open.claim(row.id(),c,3,"test");
        roster.cancel(accepted.replacement().id(),0,"实际独立取消",1L,"test");long before=audits();
        var ack=open.claim(row.id(),c,3,"test");assertThat(ack.operation()).isEqualTo(accepted.operation());assertThat(ack.replacement().cancelledAt()).isNotNull();
        deadline(f,row.id(),now().minusHours(1));assertThat(open.claim(row.id(),c,3,"test").operation()).isEqualTo(accepted.operation());
        assertThat(audits()).isEqualTo(before);assertThat(shifts(f)).isEqualTo(2);assertThat(operations(row.id())).isEqualTo(1);
    }
    @Test void shouldRecheckCurrentClaimantQualificationEvenForOriginalReceipt() {
        var f=fixture(false);var row=publish(f);var c=operation();open.claim(row.id(),c,3,"test");long before=audits();
        try{jdbc.sql("UPDATE sys_user SET role_code='AUDITOR' WHERE id=3").update();code(()->open.claim(row.id(),c,3,"test"),"ONCALL_OPEN_HANDOFF_FORBIDDEN");}
        finally{jdbc.sql("UPDATE sys_user SET role_code='OPS_MANAGER' WHERE id=3").update();}
        assertThat(audits()).isEqualTo(before);assertThat(operations(row.id())).isEqualTo(1);
    }
    @Test void shouldRejectCancelledSourceButPermitExplicitOwnerWithdrawal() {
        var f=fixture(false);var row=publish(f);roster.cancel(f.source().id(),0,"源已失效",1L,"test");long before=audits();
        code(()->open.claim(row.id(),operation(),3,"test"),"ONCALL_OPEN_HANDOFF_SOURCE_CHANGED");assertOpen(f,row.id(),before);
        var c=operation();var closed=open.withdraw(row.id(),c,2,"test");assertThat(closed.request().status()).isEqualTo("WITHDRAWN");assertThat(closed.replacement()).isNull();
        assertThat(open.withdraw(row.id(),c,2,"test").operation()).isEqualTo(closed.operation());assertThat(audits()).isEqualTo(before+1);
    }
    @Test void shouldRejectInactivePlanAndPublisherWithoutChangingResponsibility() {
        var f=fixture(false);var row=publish(f);long before=audits();
        try{jdbc.sql("UPDATE sys_user SET status='DISABLED' WHERE id=2").update();code(()->open.claim(row.id(),operation(),3,"test"),"ONCALL_OPEN_HANDOFF_FORBIDDEN");}
        finally{jdbc.sql("UPDATE sys_user SET status='ACTIVE' WHERE id=2").update();}
        jdbc.sql("UPDATE oncall_schedule SET active=FALSE WHERE id=:id").param("id",f.schedule()).update();
        code(()->open.claim(row.id(),operation(),3,"test"),"ONCALL_SCHEDULE_INACTIVE");assertOpen(f,row.id(),before);
        assertThat(open.withdraw(row.id(),operation(),2,"test").request().status()).isEqualTo("WITHDRAWN");
    }
    @Test void shouldRejectNewOverrideAndLegacyOrdinaryOverlap() {
        var f=fixture(false);var row=publish(f);var cover=roster.create(new OnCallRosterService.ShiftCommand(f.schedule(),1,f.source().startsAt(),f.source().endsAt(),true,"其他覆盖"),1L,"test");long before=audits();
        code(()->open.claim(row.id(),operation(),3,"test"),"ONCALL_OPEN_HANDOFF_OVERLAP");assertThat(audits()).isEqualTo(before);assertThat(open.list(f.schedule(),3,"AVAILABLE",null).requests()).isEmpty();
        roster.cancel(cover.id(),0,"取消冲突",1L,"test");jdbc.sql("INSERT INTO oncall_shift(schedule_id,user_id,starts_at,ends_at) VALUES (:id,1,:start,:end)")
                .param("id",f.schedule()).param("start",f.source().startsAt()).param("end",f.source().endsAt()).update();
        code(()->open.claim(row.id(),operation(),3,"test"),"ONCALL_OPEN_HANDOFF_OVERLAP");code(()->open.request(command(f),2,"test"),"ONCALL_OPEN_HANDOFF_OVERLAP");
    }
    @Test void shouldClaimOnlyRemainingSubwindowAndKeepOriginalTimes() {
        var f=fixture(true);var c=new OnCallOpenHandoffService.Command(f.source().id(),0,UUID.randomUUID().toString(),f.source().startsAt().plusMinutes(30),f.source().endsAt().minusMinutes(30),"部分时段");
        var row=open.request(c,2,"test");var before=now();var accepted=open.claim(row.id(),operation(),3,"test");
        assertThat(accepted.request().startsAt()).isEqualTo(c.startsAt());assertThat(accepted.replacement().startsAt()).isAfterOrEqualTo(before);assertThat(accepted.replacement().startsAt().getNano()).isZero();
        assertThat(accepted.replacement().endsAt()).isEqualTo(c.endsAt());assertThat(coverage.coverage(f.schedule(),accepted.replacement().startsAt(),c.endsAt()).segments()).allSatisfy(s->assertThat(s.userId()).isEqualTo(3L));
        assertThat(coverage.coverage(f.schedule(),c.endsAt(),f.source().endsAt()).segments()).allSatisfy(s->assertThat(s.userId()).isEqualTo(2L));
    }
    @Test void shouldRejectEndedClaimWithoutInventingAutomaticExpiryStatus() {
        var f=fixture(false);var row=publish(f);deadline(f,row.id(),now().minusSeconds(1));long before=audits();
        code(()->open.claim(row.id(),operation(),3,"test"),"ONCALL_OPEN_HANDOFF_EXPIRED");assertOpen(f,row.id(),before);
        assertThat(open.list(f.schedule(),3,"AVAILABLE",null).requests()).isEmpty();assertThat(open.withdraw(row.id(),operation(),2,"test").request().status()).isEqualTo("WITHDRAWN");
    }
    @Test void shouldRollbackCoverageCreationAuditFailure() {
        var f=fixture(false);var row=publish(f);long before=audits();
        doThrow(new IllegalStateException("open-create-audit-sentinel")).when(audit).recordAs(eq(3L),eq("test"),eq("ONCALL_SHIFT_CREATED"),eq("ONCALL_SHIFT"),any(),anyString());
        assertThatThrownBy(()->open.claim(row.id(),operation(),3,"test")).hasMessage("open-create-audit-sentinel");assertOpen(f,row.id(),before);
    }
    @Test void shouldRollbackCoverageOperationAndFinalClaimAuditTogether() {
        var f=fixture(false);var row=publish(f);long before=audits();
        doThrow(new IllegalStateException("open-claim-audit-sentinel")).when(audit).recordAs(eq(3L),eq("test"),eq("ONCALL_OPEN_HANDOFF_CLAIMED"),eq("ONCALL_OPEN_HANDOFF"),eq(row.id()),anyString());
        assertThatThrownBy(()->open.claim(row.id(),operation(),3,"test")).hasMessage("open-claim-audit-sentinel");assertOpen(f,row.id(),before);
    }
    @Test void shouldRollbackWithdrawalReceiptAndAuditTogether() {
        var f=fixture(false);var row=publish(f);long before=audits();
        doThrow(new IllegalStateException("open-withdraw-audit-sentinel")).when(audit).recordAs(eq(2L),eq("test"),eq("ONCALL_OPEN_HANDOFF_WITHDRAWN"),eq("ONCALL_OPEN_HANDOFF"),eq(row.id()),anyString());
        assertThatThrownBy(()->open.withdraw(row.id(),operation(),2,"test")).hasMessage("open-withdraw-audit-sentinel");assertOpen(f,row.id(),before);
    }
    @Test void shouldAllowExactlyOneWinnerBetweenDifferentClaimants() throws Exception {
        var f=fixture(false);var row=publish(f);long before=audits();var results=race(()->open.claim(row.id(),operation(),3,"test"),()->open.claim(row.id(),operation(),1,"test"));
        assertOneWinner(results,"ONCALL_OPEN_HANDOFF_VERSION_CONFLICT");assertThat(shifts(f)).isEqualTo(2);assertThat(operations(row.id())).isEqualTo(1);assertThat(audits()).isEqualTo(before+2);
    }
    @Test void shouldSerializeConcurrentOriginalClaimKeyAcknowledgements() throws Exception {
        var f=fixture(false);var row=publish(f);var c=operation();long before=audits();var results=race(()->open.claim(row.id(),c,3,"test"),()->open.claim(row.id(),c,3,"test"));
        assertThat(results).allSatisfy(r->assertThat(r).isInstanceOf(OnCallOpenHandoffService.CoverageView.class));
        var first=(OnCallOpenHandoffService.CoverageView)results.get(0);var second=(OnCallOpenHandoffService.CoverageView)results.get(1);assertThat(first.operation()).isEqualTo(second.operation());assertThat(first.request()).isEqualTo(second.request());
        assertThat(shifts(f)).isEqualTo(2);assertThat(operations(row.id())).isEqualTo(1);assertThat(audits()).isEqualTo(before+2);
    }
    @Test void shouldSerializeSameActorOperationKeyAcrossDifferentPlans() throws Exception {
        var a=fixture(false);var b=fixture(false);var first=publish(a);var second=publish(b);var c=operation();long before=audits();
        var results=race(()->open.claim(first.id(),c,3,"test"),()->open.claim(second.id(),c,3,"test"));assertOneWinner(results,"ONCALL_OPEN_HANDOFF_OPERATION_KEY_REUSED");
        assertThat(shifts(a)+shifts(b)).isEqualTo(3);assertThat(operations(first.id())+operations(second.id())).isEqualTo(1);assertThat(audits()).isEqualTo(before+2);
    }
    @Test void shouldSerializeClaimVersusOwnerWithdrawalWithoutHalfCoverage() throws Exception {
        var f=fixture(false);var row=publish(f);long before=audits();var results=race(()->open.claim(row.id(),operation(),3,"test"),()->open.withdraw(row.id(),operation(),2,"test"));
        assertOneWinner(results,"ONCALL_OPEN_HANDOFF_VERSION_CONFLICT");var facts=open.coverage(row.id());assertThat(operations(row.id())).isEqualTo(1);
        if(facts.request().status().equals("CLAIMED")){assertThat(shifts(f)).isEqualTo(2);assertThat(audits()).isEqualTo(before+2);assertThat(facts.replacement()).isNotNull();}
        else{assertThat(facts.request().status()).isEqualTo("WITHDRAWN");assertThat(shifts(f)).isEqualTo(1);assertThat(audits()).isEqualTo(before+1);assertThat(facts.replacement()).isNull();}
    }
    @Test void shouldEnforcePersonalConsentAndOriginalOperationPayload() {
        var f=fixture(false);code(()->open.request(command(f),1,"test"),"ONCALL_OPEN_HANDOFF_FORBIDDEN");var row=publish(f);
        code(()->open.claim(row.id(),operation(),2,"test"),"ONCALL_OPEN_HANDOFF_FORBIDDEN");code(()->open.withdraw(row.id(),operation(),1,"test"),"ONCALL_OPEN_HANDOFF_FORBIDDEN");
        code(()->open.claim(row.id(),operation(),4,"test"),"ONCALL_OPEN_HANDOFF_FORBIDDEN");var c=operation();open.claim(row.id(),c,3,"test");long before=audits();
        code(()->open.claim(row.id(),new OnCallOpenHandoffService.OperationCommand(0,c.operationKey(),"changed"),3,"test"),"ONCALL_OPEN_HANDOFF_OPERATION_KEY_REUSED");
        code(()->open.claim(row.id(),new OnCallOpenHandoffService.OperationCommand(1,c.operationKey(),c.reason()),3,"test"),"ONCALL_OPEN_HANDOFF_OPERATION_KEY_REUSED");
        code(()->open.claim(row.id(),operation(),3,"test"),"ONCALL_OPEN_HANDOFF_VERSION_CONFLICT");assertThat(audits()).isEqualTo(before);
    }
    @Test void shouldFilterAvailableAndMineInSqlBeforeBoundedInbox() {
        var wanted=fixture(false);var oldest=publish(wanted);var unrelated=fixture(false);jdbc.sql("UPDATE oncall_shift SET user_id=3 WHERE id=:id").param("id",unrelated.source().id()).update();
        for(int i=0;i<201;i++)open.request(command(unrelated),3,"test");
        assertThat(open.list(null,3,"AVAILABLE",null).requests()).extracting(OnCallOpenHandoffService.View::id).contains(oldest.id());
        assertThat(open.list(null,2,"MINE",null).requests()).extracting(OnCallOpenHandoffService.View::id).contains(oldest.id());
        var all=open.list(null,3,"ALL",null);assertThat(all.requests()).hasSize(200);assertThat(all.truncated()).isTrue();assertThat(open.list(null,4,"AVAILABLE",null).requests()).isEmpty();
    }
    @Test void shouldEnforceHttpAuthenticationAndExplicitVersions() throws Exception {
        var f=fixture(false);var c=command(f);ObjectNode body=json.valueToTree(c);String owner=login("zhangwei"),auditor=login("auditor"),claimant=login("lina");
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).header("Authorization",auditor).contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isForbidden());
        var missing=body.deepCopy();missing.remove("sourceVersion");mvc.perform(post(BASE).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(missing.toString())).andExpect(status().isBadRequest());
        var row=publish(f);ObjectNode op=json.valueToTree(operation());var noVersion=op.deepCopy();noVersion.remove("version");
        for(String action:List.of("claims","withdrawals"))mvc.perform(post(BASE+"/"+row.id()+"/"+action).header("Authorization",action.equals("claims")?claimant:owner).contentType(MediaType.APPLICATION_JSON).content(noVersion.toString())).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).header("Authorization",owner).param("scope","bad")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE+"/9223372036854775807").header("Authorization",owner)).andExpect(status().isNotFound());
        assertOpen(f,row.id(),audits());
    }
    @Test void shouldRecheckActualPersistedDeadlineAfterScheduleLockWait() throws Exception {
        var f=fixture(false);var row=publish(f);var requested=now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(4);var persisted=deadline(f,row.id(),requested);long before=audits();
        var results=afterScheduleLock(f,()->open.claim(row.id(),operation(),3,"test"),()->barrier("shouldRecheckActualPersistedDeadlineAfterScheduleLockWait",requested,persisted));
        assertThat(results).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ONCALL_OPEN_HANDOFF_EXPIRED"));assertOpen(f,row.id(),before);
    }
    @Test void shouldRollbackWhenFinalClaimAuditCrossesPersistedDeadline() throws Exception {
        var f=fixture(false);var row=publish(f);var requested=now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(4);var persisted=deadline(f,row.id(),requested);long before=audits();
        doAnswer(call->{call.callRealMethod();barrier("shouldRollbackWhenFinalClaimAuditCrossesPersistedDeadline",requested,persisted);return null;}).when(audit)
                .recordAs(eq(3L),eq("test"),eq("ONCALL_OPEN_HANDOFF_CLAIMED"),eq("ONCALL_OPEN_HANDOFF"),eq(row.id()),anyString());
        code(()->open.claim(row.id(),operation(),3,"test"),"ONCALL_OPEN_HANDOFF_EXPIRED");assertOpen(f,row.id(),before);
    }
    @Test void shouldReadLatestQualificationAfterScheduleLockWait() throws Exception {
        var f=fixture(false);var row=publish(f);long before=audits();
        try{var result=afterScheduleLock(f,()->open.claim(row.id(),operation(),3,"test"),()->jdbc.sql("UPDATE sys_user SET role_code='AUDITOR' WHERE id=3").update());
            assertThat(result).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ONCALL_OPEN_HANDOFF_FORBIDDEN"));assertOpen(f,row.id(),before);}
        finally{jdbc.sql("UPDATE sys_user SET role_code='OPS_MANAGER' WHERE id=3").update();}
    }
    @Test void shouldRollbackPublicationWhenItsAuditFails() {
        var f=fixture(false);long before=audits();doThrow(new IllegalStateException("open-publish-audit-sentinel")).when(audit)
                .recordAs(eq(2L),eq("test"),eq("ONCALL_OPEN_HANDOFF_REQUESTED"),eq("ONCALL_OPEN_HANDOFF"),any(),anyString());
        assertThatThrownBy(()->open.request(command(f),2,"test")).hasMessage("open-publish-audit-sentinel");assertThat(open.list(f.schedule(),2,"ALL",null).requests()).isEmpty();assertThat(audits()).isEqualTo(before);assertThat(shifts(f)).isEqualTo(1);
    }

    private Object afterScheduleLock(Fixture f,Callable<?> action,ThrowingRunnable releaseWork) throws Exception {
        var executor=Executors.newSingleThreadExecutor();var entered=new CountDownLatch(1);
        try(var connection=datasource.getConnection()) {connection.setAutoCommit(false);
            try(var stmt=connection.prepareStatement("SELECT id FROM oncall_schedule WHERE id=? FOR UPDATE")){stmt.setLong(1,f.schedule());try(var rows=stmt.executeQuery()){assertThat(rows.next()).isTrue();}}
            var waiting=executor.submit(()->{entered.countDown();return outcome(action);});assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));assertThat(waiting.isDone()).isFalse();
            releaseWork.run();connection.commit();return waiting.get(15,TimeUnit.SECONDS);
        }finally{executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    private LocalDateTime deadline(Fixture f,long id,LocalDateTime requested) {
        var start=requested.minusHours(1);jdbc.sql("UPDATE oncall_shift SET starts_at=:start,ends_at=:end WHERE id=:id").param("id",f.source().id()).param("start",start).param("end",requested).update();
        jdbc.sql("UPDATE oncall_open_handoff SET starts_at=:start,ends_at=:end WHERE id=:id").param("id",id).param("start",start).param("end",requested).update();
        var stored=open.get(id).endsAt();assertThat(jdbc.sql("SELECT ends_at FROM oncall_shift WHERE id=:id").param("id",f.source().id()).query(LocalDateTime.class).single()).isEqualTo(stored);
        if(getClass().getSimpleName().startsWith("MySql"))assertThat(stored.getNano()).isZero();return stored;
    }
    private void barrier(String name,LocalDateTime requested,LocalDateTime stored) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(now().isBefore(stored)&&System.nanoTime()<deadline)LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        var released=now();assertThat(released).isAfterOrEqualTo(stored);System.out.println("CP97_OPEN_HANDOFF_TIME_BARRIER "+json.writeValueAsString(Map.of("case",name,"requestedEnd",requested.toString(),"persistedEnd",stored.toString(),"releasedDatabaseNow",released.toString(),"releasedAfterPersistedEnd",true)));
    }
    private List<Object> race(Callable<?> a,Callable<?> b) throws Exception {var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{var first=executor.submit(()->{assertThat(gate.await(5,TimeUnit.SECONDS)).isTrue();return outcome(a);});var second=executor.submit(()->{assertThat(gate.await(5,TimeUnit.SECONDS)).isTrue();return outcome(b);});gate.countDown();return List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));}
        finally{gate.countDown();executor.shutdownNow();assertThat(executor.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}}
    private Object outcome(Callable<?> action){try{return action.call();}catch(ApiException e){return e;}catch(Exception e){throw new IllegalStateException(e);}}
    private void assertOneWinner(List<Object> results,String code){assertThat(results.stream().filter(r->r instanceof OnCallOpenHandoffService.CoverageView).count()).isEqualTo(1);assertThat(results.stream().filter(r->r instanceof ApiException).toList()).singleElement().satisfies(r->assertThat(((ApiException)r).code()).isEqualTo(code));}
    private Fixture fixture(boolean ongoing){long resource=insert(jdbc.sql("INSERT INTO cmdb_resource(resource_code,resource_type,name,environment,status) VALUES (:code,'APPLICATION','开放接班独立服务','TEST','RUNNING')").param("code","OPEN-"+UUID.randomUUID()));long schedule=insert(jdbc.sql("INSERT INTO oncall_schedule(service_resource_id,name) VALUES (:id,'开放接班独立计划')").param("id",resource));PlanMembershipFixtures.grant(jdbc,schedule,1,2,3);var at=now().truncatedTo(ChronoUnit.SECONDS);var start=ongoing?at.minusHours(1):at.plusDays(1);return new Fixture(schedule,roster.create(new OnCallRosterService.ShiftCommand(schedule,2,start,start.plusHours(4),false,"开放接班源"),1L,"test"));}
    private OnCallOpenHandoffService.Command command(Fixture f){return new OnCallOpenHandoffService.Command(f.source().id(),0,UUID.randomUUID().toString(),f.source().startsAt(),f.source().endsAt()," 开放接班🙂 ");}
    private OnCallOpenHandoffService.View publish(Fixture f){return open.request(command(f),2,"test");}
    private OnCallOpenHandoffService.OperationCommand operation(){return new OnCallOpenHandoffService.OperationCommand(0,UUID.randomUUID().toString()," 本人主动认领🙂 ");}
    private void assertOpen(Fixture f,long id,long auditCount){var facts=open.coverage(id);assertThat(facts.request().status()).isEqualTo("OPEN");assertThat(facts.request().version()).isZero();assertThat(facts.request().claimedBy()).isNull();assertThat(facts.replacement()).isNull();assertThat(facts.operation()).isNull();assertThat(operations(id)).isZero();assertThat(shifts(f)).isEqualTo(1);assertThat(audits()).isEqualTo(auditCount);}
    private long shifts(Fixture f){return jdbc.sql("SELECT COUNT(*) FROM oncall_shift WHERE schedule_id=:id").param("id",f.schedule()).query(Long.class).single();}
    private long operations(long id){return jdbc.sql("SELECT COUNT(*) FROM oncall_open_handoff_operation WHERE handoff_id=:id").param("id",id).query(Long.class).single();}
    private long audits(){return jdbc.sql("SELECT COUNT(*) FROM audit_log").query(Long.class).single();}
    private LocalDateTime now(){return jdbc.sql("SELECT CURRENT_TIMESTAMP(6)").query((rs,n)->rs.getObject(1,LocalDateTime.class)).single();}
    private long insert(JdbcClient.StatementSpec stmt){var key=new GeneratedKeyHolder();stmt.update(key,"id");return key.getKey().longValue();}
    private void code(Runnable action,String expected){assertThatThrownBy(action::run).isInstanceOf(ApiException.class).extracting("code").isEqualTo(expected);}
    private String login(String username)throws Exception{var response=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\""+username+"\",\"password\":\"OpsPilot@2026\"}")).andExpect(status().isOk()).andReturn().getResponse();return "Bearer "+json.readTree(response.getContentAsString()).path("data").path("accessToken").asText();}
    private record Fixture(long schedule,OnCallRosterService.ShiftView source){}
    private interface ThrowingRunnable{void run()throws Exception;}
}
