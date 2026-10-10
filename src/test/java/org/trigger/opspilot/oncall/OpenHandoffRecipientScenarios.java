package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Identical production-service cases on H2 and a test-owned real MySQL. */
abstract class OpenHandoffRecipientScenarios {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource datasource;
    @Autowired OnCallPlanMembershipService members;
    @Autowired OnCallRosterService roster;
    @Autowired OnCallOpenHandoffService open;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @SpyBean OnCallOpenHandoffRecipients recipients;
    @SpyBean AuditService audit;

    @BeforeEach void databaseIdentity(TestInfo test) throws Exception {
        try (var connection=datasource.getConnection()) {
            var metadata=connection.getMetaData();
            if (getClass().getSimpleName().startsWith("MySql")) {
                assertThat(metadata.getDatabaseProductName()).isEqualTo("MySQL");
                assertThat(metadata.getDatabaseProductVersion()).startsWith("8.4.");
                assertThat(connection.getCatalog()).isEqualTo("opspilot_open_recipient_test");
            }
            assertThat(count("flyway_schema_history","version='40' AND success=TRUE")).isEqualTo(1);
            assertThat(count("flyway_schema_history","version='41' AND success=TRUE")).isEqualTo(1);
            assertThat(count("flyway_schema_history","version IS NOT NULL AND success=TRUE")).isEqualTo(41);
            System.out.println("OPEN_RECIPIENT_DATABASE "+json.writeValueAsString(Map.of("case",test.getTestMethod().orElseThrow().getName(),
                    "product",metadata.getDatabaseProductName(),"version",metadata.getDatabaseProductVersion(),"schema",connection.getCatalog(),"migration40",true,"migration41",true)));
        }
    }
    @AfterEach void resetSpies() { reset(audit);reset(AopTestUtils.<OnCallOpenHandoffRecipients>getUltimateTargetObject(recipients)); }

    @Test void shouldUseOnlyThisPlansCurrentRespondersAndExcludePublisherAndManagerOnly() {
        long target=user("ON_CALL"), manager=user("OPS_MANAGER"), elsewhere=user("ON_CALL"), disabled=user("ON_CALL"), auditor=user("ON_CALL");
        var f=fixture(target);grant(f.plan(),manager,false,true);grant(plan(),elsewhere,true,false);grant(f.plan(),disabled,true,false);grant(f.plan(),auditor,true,false);
        account(disabled,"status","DISABLED");account(auditor,"role_code","AUDITOR");
        var row=publish(f);var view=recipients.get(row.id());
        assertThat(view.publication().recipients()).extracting(OnCallOpenHandoffRecipients.Recipient::userId).containsExactly(target);
        assertThat(view.eligibleOriginalRecipientIds()).containsExactly(target);
        denied(()->open.claim(row.id(),operation(),manager,"test"),"ONCALL_PLAN_RESPONSE_FORBIDDEN");
        denied(()->open.claim(row.id(),operation(),1,"test"),"ONCALL_PLAN_RESPONSE_FORBIDDEN");
        denied(()->open.claim(row.id(),operation(),elsewhere,"test"),"ONCALL_PLAN_RESPONSE_FORBIDDEN");
        assertThat(open.claim(row.id(),operation(),target,"test").request().claimedBy()).isEqualTo(target);
    }

    @Test void shouldFreezeOriginalMemberVersionAndNameWithoutAddingLaterGrants() {
        long target=user("ON_CALL"), other=user("ON_CALL"), later=user("ON_CALL");var f=fixture(target,other);var row=publish(f);var saved=recipients.get(row.id()).publication();String raw=payload(row.id());
        account(target,"display_name","changed after publication");revoke(f.plan(),target);grant(f.plan(),later,true,false);
        var current=recipients.get(row.id());assertThat(current.publication()).isEqualTo(saved);assertThat(payload(row.id())).isEqualTo(raw);
        assertThat(current.eligibleOriginalRecipientIds()).containsExactly(other);assertThat(current.deliveryImplemented()).isTrue();
        assertThat(saved.recipients()).allSatisfy(r->assertThat(r.memberVersion()).isZero());
        assertThat(open.list(f.plan(),other,"AVAILABLE",null).requests()).extracting(OnCallOpenHandoffService.View::id).contains(row.id());
        denied(()->open.claim(row.id(),operation(),target,"test"),"ONCALL_PLAN_RESPONSE_FORBIDDEN");
    }

    @Test void shouldRecheckRecipientAccountAndNotResurrectRevokedMembership() {
        long target=user("ON_CALL");var f=fixture(target);var row=publish(f);var saved=recipients.get(row.id()).publication();
        account(target,"status","DISABLED");assertThat(recipients.get(row.id()).eligibleOriginalRecipientIds()).isEmpty();
        account(target,"status","ACTIVE");account(target,"role_code","AUDITOR");assertThat(recipients.get(row.id()).eligibleOriginalRecipientIds()).isEmpty();
        account(target,"role_code","ON_CALL");assertThat(recipients.get(row.id()).eligibleOriginalRecipientIds()).containsExactly(target);
        revoke(f.plan(),target);account(target,"status","DISABLED");account(target,"status","ACTIVE");
        assertThat(recipients.get(row.id()).eligibleOriginalRecipientIds()).isEmpty();assertThat(recipients.get(row.id()).publication()).isEqualTo(saved);
    }

    @Test void shouldRecheckPublisherResponseAndAccountBeforeDeliveryObservation() {
        long target=user("ON_CALL");var f=fixture(target);var row=publish(f);var saved=recipients.get(row.id()).publication();
        account(f.owner(),"role_code","AUDITOR");assertThat(recipients.get(row.id()).eligibleOriginalRecipientIds()).isEmpty();account(f.owner(),"role_code","ON_CALL");
        account(f.owner(),"status","DISABLED");assertThat(recipients.get(row.id()).eligibleOriginalRecipientIds()).isEmpty();account(f.owner(),"status","ACTIVE");
        revoke(f.plan(),f.owner());assertThat(recipients.get(row.id()).eligibleOriginalRecipientIds()).isEmpty();
        denied(()->open.claim(row.id(),operation(),target,"test"),"ONCALL_PLAN_RESPONSE_FORBIDDEN");assertThat(recipients.get(row.id()).publication()).isEqualTo(saved);
    }

    @Test void shouldStopCandidatesForInactivePlanAndChangedOrCancelledSource() {
        long target=user("ON_CALL");var f=fixture(target);var row=publish(f);var saved=recipients.get(row.id()).publication();
        jdbc.sql("UPDATE oncall_schedule SET active=FALSE WHERE id=:id").param("id",f.plan()).update();assertThat(recipients.get(row.id()).eligibleOriginalRecipientIds()).isEmpty();
        denied(()->open.claim(row.id(),operation(),target,"test"),"ONCALL_SCHEDULE_INACTIVE");jdbc.sql("UPDATE oncall_schedule SET active=TRUE WHERE id=:id").param("id",f.plan()).update();
        jdbc.sql("UPDATE oncall_shift SET version=version+1 WHERE id=:id").param("id",f.source().id()).update();assertThat(recipients.get(row.id()).eligibleOriginalRecipientIds()).isEmpty();
        denied(()->open.claim(row.id(),operation(),target,"test"),"ONCALL_OPEN_HANDOFF_SOURCE_CHANGED");
        jdbc.sql("UPDATE oncall_shift SET version=0,cancelled_at=CURRENT_TIMESTAMP(6),cancellation_reason='test cancellation' WHERE id=:id").param("id",f.source().id()).update();
        assertThat(recipients.get(row.id()).eligibleOriginalRecipientIds()).isEmpty();assertThat(recipients.get(row.id()).publication()).isEqualTo(saved);
    }

    @Test void shouldStopCandidatesWhenAnotherActualOverrideOverlapsRemainingWindow() {
        long target=user("ON_CALL");var f=fixture(target);var row=publish(f);var saved=recipients.get(row.id()).publication();
        roster.createManaged(new OnCallRosterService.ShiftCommand(f.plan(),target,f.source().startsAt(),f.source().endsAt(),true,"actual overlap"),1,"test");
        assertThat(recipients.get(row.id()).eligibleOriginalRecipientIds()).isEmpty();denied(()->open.claim(row.id(),operation(),target,"test"),"ONCALL_OPEN_HANDOFF_OVERLAP");
        assertThat(recipients.get(row.id()).publication()).isEqualTo(saved);
    }

    @Test void shouldKeepPublicationAfterClaimAndNeverTreatSnapshotAsActiveCoverage() {
        long target=user("ON_CALL");var f=fixture(target);var row=publish(f);var saved=recipients.get(row.id()).publication();
        var claimed=open.claim(row.id(),operation(),target,"test");var view=recipients.get(row.id());
        assertThat(view.currentRequestStatus()).isEqualTo("CLAIMED");assertThat(view.currentRequestVersion()).isEqualTo(1);assertThat(view.eligibleOriginalRecipientIds()).isEmpty();assertThat(view.publication()).isEqualTo(saved);
        assertThat(claimed.replacement()).isNotNull();assertThat(saved.eventVersion()).isZero();assertThat(view.deliveryImplemented()).isTrue();
    }

    @Test void shouldKeepPublicationAfterWithdrawalAndOriginalKeyAcknowledgement() {
        long target=user("ON_CALL");var f=fixture(target);var command=command(f);var row=open.request(command,f.owner(),"test");String raw=payload(row.id());long before=count("oncall_open_handoff_publication","1=1");
        open.withdraw(row.id(),operation(),f.owner(),"test");assertThat(recipients.get(row.id()).eligibleOriginalRecipientIds()).isEmpty();
        grant(f.plan(),user("ON_CALL"),true,false);assertThat(open.request(command,f.owner(),"test").status()).isEqualTo("WITHDRAWN");
        assertThat(payload(row.id())).isEqualTo(raw);assertThat(count("oncall_open_handoff_publication","1=1")).isEqualTo(before);
    }

    @Test void shouldStopExpiredCandidatesUsingDatabaseTimeWithoutChangingFrozenWindow() {
        long target=user("ON_CALL");var f=fixture(target);var row=publish(f);var saved=recipients.get(row.id()).publication();var end=now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(1);
        jdbc.sql("UPDATE oncall_open_handoff SET starts_at=:start,ends_at=:end WHERE id=:id").param("start",end.minusHours(1)).param("end",end).param("id",row.id()).update();
        jdbc.sql("UPDATE oncall_shift SET starts_at=:start,ends_at=:end WHERE id=:id").param("start",end.minusHours(1)).param("end",end).param("id",f.source().id()).update();
        assertThat(recipients.get(row.id()).eligibleOriginalRecipientIds()).isEmpty();assertThat(recipients.get(row.id()).publication()).isEqualTo(saved);
        denied(()->open.claim(row.id(),operation(),target,"test"),"ONCALL_OPEN_HANDOFF_EXPIRED");
    }

    @Test void shouldPersistEmptyOriginalAudienceAndNeverBackfillNewMembersOnReplay() {
        var f=fixture();var c=command(f);var row=open.request(c,f.owner(),"test");var saved=recipients.get(row.id()).publication();assertThat(saved.recipients()).isEmpty();
        grant(f.plan(),user("ON_CALL"),true,false);open.request(c,f.owner(),"test");assertThat(recipients.get(row.id()).publication()).isEqualTo(saved);assertThat(recipients.get(row.id()).eligibleOriginalRecipientIds()).isEmpty();
    }

    @Test void shouldKeepLegacyPublicationUnavailableWithoutInferringHistoricalRecipients() {
        long target=user("ON_CALL");var f=fixture(target);var c=command(f);long id=insert(jdbc.sql("""
                INSERT INTO oncall_open_handoff(schedule_id,source_shift_id,source_version,requester_id,request_key,starts_at,ends_at,reason)
                VALUES (:plan,:source,0,:owner,:key,:start,:end,:reason)
                """).param("plan",f.plan()).param("source",f.source().id()).param("owner",f.owner()).param("key",c.requestKey()).param("start",c.startsAt()).param("end",c.endsAt()).param("reason",c.reason()));
        var legacy=recipients.get(id);assertThat(legacy.publicationSnapshotAvailable()).isFalse();assertThat(legacy.publication()).isNull();assertThat(legacy.eligibleOriginalRecipientIds()).isEmpty();
        assertThat(open.request(c,f.owner(),"test").id()).isEqualTo(id);assertThat(recipients.get(id).publicationSnapshotAvailable()).isFalse();
    }

    @Test void shouldRollbackRequestAndAuditWhenSnapshotInsertFailsAfterActualInsert() {
        var f=fixture(user("ON_CALL"));var before=counts();
        var target=AopTestUtils.<OnCallOpenHandoffRecipients>getUltimateTargetObject(recipients);
        doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("after actual snapshot insert");}).when(target).freeze(any());
        assertThatThrownBy(()->publish(f)).isInstanceOf(IllegalStateException.class);assertThat(counts()).isEqualTo(before);
    }

    @Test void shouldRollbackWholePublicationWhenAuditFailsAfterActualInsert() {
        var f=fixture(user("ON_CALL"));var before=counts();
        doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("after actual audit insert");}).when(audit).recordAs(anyLong(),anyString(),eq("ONCALL_OPEN_HANDOFF_REQUESTED"),anyString(),anyLong(),anyString());
        assertThatThrownBy(()->publish(f)).isInstanceOf(IllegalStateException.class);assertThat(counts()).isEqualTo(before);
    }

    @Test void shouldExposeAuthenticatedReadOnlySnapshotWithoutSecretsAndRequireEnclosingTransaction() throws Exception {
        var f=fixture(user("ON_CALL"));var row=publish(f);long before=count("oncall_open_handoff_publication","1=1");
        mvc.perform(get("/api/v1/on-call/open-handoffs/"+row.id()+"/publication")).andExpect(status().isUnauthorized());
        var login=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username","auditor","password","OpsPilot@2026")))).andExpect(status().isOk()).andReturn().getResponse();
        String token=json.readTree(login.getContentAsString()).path("data").path("accessToken").asText();
        var response=mvc.perform(get("/api/v1/on-call/open-handoffs/"+row.id()+"/publication").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deliveryImplemented").value(true)).andExpect(jsonPath("$.data.publicationSnapshotAvailable").value(true)).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("password","requestKey","reason","accessToken");
        assertThatThrownBy(()->recipients.freeze(row)).isInstanceOf(IllegalTransactionStateException.class);assertThat(count("oncall_open_handoff_publication","1=1")).isEqualTo(before);
    }

    private record Fixture(long plan,long owner,OnCallRosterService.ShiftView source) {}
    private Fixture fixture(long... targets) { long p=plan(),owner=user("ON_CALL");grant(p,owner,true,false);for(long t:targets)grant(p,t,true,false);var at=now().truncatedTo(ChronoUnit.SECONDS).plusDays(1);return new Fixture(p,owner,roster.createManaged(new OnCallRosterService.ShiftCommand(p,owner,at,at.plusHours(4),false,"recipient fixture"),1,"test")); }
    private long plan() { return insert(jdbc.sql("INSERT INTO oncall_schedule(service_resource_id,name) VALUES (3,:name)").param("name","recipient-"+UUID.randomUUID())); }
    private long user(String role) { return insert(jdbc.sql("INSERT INTO sys_user(username,password_hash,display_name,role_code) SELECT :name,password_hash,'original recipient',:role FROM sys_user WHERE id=2").param("name","recipient-"+UUID.randomUUID()).param("role",role)); }
    private long insert(JdbcClient.StatementSpec statement) {var key=new GeneratedKeyHolder();statement.update(key,"id");return key.getKey().longValue();}
    private void grant(long p,long user,boolean respond,boolean manage) {Integer version=members.list(p).stream().filter(m->m.userId()==user).map(OnCallPlanMembershipService.MemberView::version).findFirst().orElse(null);members.change(p,new OnCallPlanMembershipService.Command(user,version,true,respond,manage,UUID.randomUUID().toString(),"explicit recipient grant"),1,"test");}
    private void revoke(long p,long user) {int version=members.list(p).stream().filter(m->m.userId()==user).findFirst().orElseThrow().version();members.change(p,new OnCallPlanMembershipService.Command(user,version,false,false,false,UUID.randomUUID().toString(),"recipient revoke"),1,"test");}
    private void account(long id,String column,String value) {assertThat(column).isIn("display_name","role_code","status");jdbc.sql("UPDATE sys_user SET "+column+"=:value WHERE id=:id").param("value",value).param("id",id).update();}
    private OnCallOpenHandoffService.Command command(Fixture f) {return new OnCallOpenHandoffService.Command(f.source().id(),0,UUID.randomUUID().toString(),f.source().startsAt(),f.source().endsAt(),"original publication");}
    private OnCallOpenHandoffService.View publish(Fixture f) {return open.request(command(f),f.owner(),"test");}
    private OnCallOpenHandoffService.OperationCommand operation() {return new OnCallOpenHandoffService.OperationCommand(0,UUID.randomUUID().toString(),"explicit voluntary claim");}
    private LocalDateTime now() {return jdbc.sql("SELECT CURRENT_TIMESTAMP(6)").query((rs,n)->rs.getObject(1,LocalDateTime.class)).single();}
    private String payload(long id) {return jdbc.sql("SELECT snapshot_json FROM oncall_open_handoff_publication WHERE handoff_id=:id").param("id",id).query(String.class).single();}
    private long count(String table,String where) {return jdbc.sql("SELECT COUNT(*) FROM "+table+" WHERE "+where).query(Long.class).single();}
    private Map<String,Long> counts() {return Map.of("requests",count("oncall_open_handoff","1=1"),"publications",count("oncall_open_handoff_publication","1=1"),"audits",count("audit_log","1=1"),"shifts",count("oncall_shift","1=1"),"operations",count("oncall_open_handoff_operation","1=1"));}
    private void denied(Runnable call,String code) {assertThatThrownBy(call::run).isInstanceOf(ApiException.class).extracting("code").isEqualTo(code);}
}
