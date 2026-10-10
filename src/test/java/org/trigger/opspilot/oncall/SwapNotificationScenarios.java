package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Identical business and real HTTP assertions for H2 and actual MySQL, never fabricated JDBC labels. */
abstract class SwapNotificationScenarios {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource datasource;
    @Autowired ObjectMapper json;
    @Autowired OnCallSwapService swaps;
    @Autowired OnCallRosterService roster;
    @Autowired OnCallSwapNotifications notifications;
    @Autowired OnCallSwapNotificationProperties properties;
    @Autowired OnCallSwapNotificationJob job;
    @Autowired TransactionTemplate transactions;
    @Autowired MockMvc mvc;
    @SpyBean AuditService audit;
    static Receiver receiver;
    List<UserState> originalUsers;
    String databaseProduct;

    @DynamicPropertySource static void receiverUrl(DynamicPropertyRegistry registry) {
        registry.add("opspilot.oncall.swap.notification.url",()->"http://127.0.0.1:"+fixtureReceiver().server.getAddress().getPort()+"/notify");
    }
    static synchronized Receiver fixtureReceiver() { if(receiver==null)receiver=new Receiver();return receiver; }
    @AfterAll static synchronized void closeReceiver() { if(receiver!=null){receiver.server.stop(0);receiver.pool.shutdownNow();receiver=null;} }
    @BeforeEach void before(TestInfo test) throws Exception {
        reset(audit);jdbc.sql("DELETE FROM oncall_swap_notification").update();
        originalUsers=jdbc.sql("SELECT id,display_name,status,role_code FROM sys_user WHERE id IN (2,3)")
                .query((rs,n)->new UserState(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4))).list();
        fixtureReceiver().reset();
        try(var connection=datasource.getConnection()){
            var metadata=connection.getMetaData();databaseProduct=metadata.getDatabaseProductName();if(getClass().getSimpleName().startsWith("MySql")){assertThat(databaseProduct).isEqualTo("MySQL");assertThat(metadata.getDatabaseProductVersion()).startsWith("8.4.");}
            assertThat(jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version='35' AND success=TRUE").query(Long.class).single()).isEqualTo(1);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE version='36' AND success=TRUE").query(Long.class).single()).isEqualTo(1);
            System.out.println("CP87_SWAP_NOTIFICATION_DATABASE "+json.writeValueAsString(Map.of("case",test.getTestMethod().orElseThrow().getName(),"product",metadata.getDatabaseProductName(),"version",metadata.getDatabaseProductVersion(),"schema",connection.getCatalog(),"migration36",true)));
        }
    }
    @AfterEach void after() {
        for(var u:originalUsers)jdbc.sql("UPDATE sys_user SET display_name=:name,status=:status,role_code=:role WHERE id=:id")
                .param("id",u.id()).param("name",u.name()).param("status",u.status()).param("role",u.role()).update();
        fixtureReceiver().release.countDown();reset(audit);
    }

    @Test void shouldEnqueueRequestAndBothDecisionRecipientsOnceWithoutDecidingFromReceipt() {
        var f=pair();var command=command(f);var pending=swaps.request(command,2,"test");
        assertThat(notifications.list(pending.id()).deliveries()).singleElement().satisfies(n->{assertThat(n.recipientId()).isEqualTo(3);assertThat(n.eventVersion()).isZero();});
        assertThat(swaps.request(command,2,"test")).isEqualTo(pending);assertThat(notifications.dispatchDue()).isEqualTo(1);
        assertThat(swaps.get(pending.id()).status()).isEqualTo("PENDING");assertThat(shifts(f)).isEqualTo(2);
        var accepted=swaps.decide(pending.id(),decision("ACCEPTED"),3,"test");
        swaps.decide(pending.id(),decision("ACCEPTED"),3,"test");swaps.request(command,2,"test");
        assertThat(notifications.list(pending.id()).deliveries()).hasSize(3);assertThat(notifications.dispatchDue()).isEqualTo(2);
        assertThat(notifications.list(pending.id()).deliveries()).allSatisfy(n->assertThat(n.status()).isEqualTo("DELIVERED"));
        assertThat(accepted.status()).isEqualTo("ACCEPTED");assertThat(shifts(f)).isEqualTo(4);assertThat(receiver.bodies).hasSize(3);
    }
    @Test void shouldRollbackRequestAuditAndNotificationTogether() {
        var f=pair();long count=swapsCount();
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("controlled requested audit failure");}).when(audit).recordAs(anyLong(),anyString(),eq("ONCALL_SWAP_REQUESTED"),anyString(),any(),anyString());
        assertThatThrownBy(()->swaps.request(command(f),2,"test")).isInstanceOf(IllegalStateException.class);
        assertThat(swapsCount()).isEqualTo(count);assertThat(notifications.dispatchDue()).isZero();assertThat(receiver.bodies).isEmpty();
    }
    @Test void shouldRollbackBothOverridesAndNotificationRowsWhenEnqueueFails() {
        var f=pair();var pending=swaps.request(command(f),2,"test");long count=auditCount();
        jdbc.sql("ALTER TABLE oncall_swap_notification ADD CONSTRAINT cp87_reject_decision CHECK(event_status='PENDING')").update();
        try{assertThatThrownBy(()->swaps.decide(pending.id(),decision("ACCEPTED"),3,"test"))
                .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,error->{
                    assertThat(databaseProduct).isIn("H2","MySQL");
                    assertThat(error.getMostSpecificCause()).isInstanceOf(java.sql.SQLException.class);
                    var sql=(java.sql.SQLException)error.getMostSpecificCause();boolean mysql=databaseProduct.equals("MySQL");
                    assertThat(sql.getErrorCode()).isEqualTo(mysql?3819:23513);
                    assertThat(sql.getSQLState()).isEqualTo(mysql?"HY000":"23513");
                    assertThat(sql.getMessage()).containsIgnoringCase("cp87_reject_decision");
                });}
        finally{jdbc.sql("ALTER TABLE oncall_swap_notification DROP CONSTRAINT cp87_reject_decision").update();}
        assertThat(swaps.get(pending.id()).status()).isEqualTo("PENDING");assertThat(shifts(f)).isEqualTo(2);assertThat(auditCount()).isEqualTo(count);
        assertThat(notifications.list(pending.id()).deliveries()).hasSize(1);
    }
    @Test void shouldKeepFrozenPayloadAndStableKeyAcrossRealTransientRetry() {
        var pending=swaps.request(command(pair()),2,"test");receiver.status.set(503);notifications.dispatchDue();var first=only(pending.id());
        assertThat(first.status()).isEqualTo("PENDING");assertThat(first.attempts()).isEqualTo(1);assertThat(first.lastHttpStatus()).isEqualTo(503);assertThat(notifications.dispatchDue()).isZero();
        jdbc.sql("UPDATE sys_user SET display_name='后来改名' WHERE id=3").update();due(first.id());receiver.status.set(204);notifications.dispatchDue();
        assertThat(only(pending.id()).status()).isEqualTo("DELIVERED");assertThat(only(pending.id()).attempts()).isEqualTo(2);
        assertThat(receiver.keys).hasSize(2);assertThat(receiver.keys.get(0)).isEqualTo(receiver.keys.get(1));assertThat(receiver.bodies.get(0)).isEqualTo(receiver.bodies.get(1));
        var body=jsonTree(receiver.bodies.get(0));assertThat(body.path("recipientName").asText()).isEqualTo(originalUsers.stream().filter(u->u.id()==3).findFirst().orElseThrow().name());
        assertThat(body.path("humanConfirmationRequired").asBoolean()).isTrue();assertThat(body.path("currentCoverageMustBeReadSeparately").asBoolean()).isTrue();
        assertThat(body.has("reason")).isFalse();assertThat(body.has("requestKey")).isFalse();assertThat(receiver.authCorrect).containsExactly(true,true);
    }
    @Test void shouldSkipSupersededRequestButNotifyBothParticipantsOfRejection() {
        var pending=swaps.request(command(pair()),2,"test");swaps.decide(pending.id(),decision("REJECTED"),3,"test");notifications.dispatchDue();
        var rows=notifications.list(pending.id()).deliveries();assertThat(rows.get(0).status()).isEqualTo("SKIPPED");assertThat(rows.get(0).lastErrorCode()).isEqualTo("EVENT_SUPERSEDED");
        assertThat(rows.stream().filter(n->n.eventStatus().equals("REJECTED"))).hasSize(2).allSatisfy(n->assertThat(n.status()).isEqualTo("DELIVERED"));assertThat(receiver.bodies).hasSize(2);
    }
    @Test void shouldSkipStartedCancelledOrCoveredRequestSourcesBeforeSending() {
        for(String mutation:List.of("started","cancelled","covered")){
            var f=pair();var pending=swaps.request(command(f),2,"test");
            if(mutation.equals("started"))jdbc.sql("UPDATE oncall_shift SET starts_at=:past WHERE id=:id").param("past",now().minusDays(1)).param("id",f.first().id()).update();
            else if(mutation.equals("cancelled"))roster.cancel(f.first().id(),0,"controlled source invalidation",1L,"test");
            else roster.create(new OnCallRosterService.ShiftCommand(f.first().scheduleId(),3,f.first().startsAt(),f.first().endsAt(),true,"覆盖"),1L,"test");
            notifications.dispatchDue();assertThat(only(pending.id()).status()).isEqualTo("SKIPPED");assertThat(only(pending.id()).lastErrorCode()).isEqualTo("REQUEST_NO_LONGER_ACTIONABLE");
        }assertThat(receiver.bodies).isEmpty();
    }
    @Test void shouldSkipIneligibleRecipientWithoutExposingPayload() {
        var pending=swaps.request(command(pair()),2,"test");jdbc.sql("UPDATE sys_user SET role_code='AUDITOR' WHERE id=3").update();notifications.dispatchDue();
        assertThat(only(pending.id()).lastErrorCode()).isEqualTo("RECIPIENT_INELIGIBLE");assertThat(receiver.bodies).isEmpty();
    }
    @Test void shouldBoundAttemptsAndNotPersistSensitiveReceiverBody() {
        var pending=swaps.request(command(pair()),2,"test");receiver.status.set(503);
        for(int i=0;i<3;i++){notifications.dispatchDue();if(i<2)due(only(pending.id()).id());}
        var failed=only(pending.id());assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.attempts()).isEqualTo(3);assertThat(failed.totalAttempts()).isEqualTo(3);assertThat(failed.lastErrorCode()).isEqualTo("HTTP_503");
        assertThat(notifications.dispatchDue()).isZero();assertThat(jsonString(notifications.list(pending.id()))).doesNotContain("sensitive-receiver-sentinel","cp87-notification-token");assertThat(receiver.bodies).hasSize(3);
    }
    @Test void shouldRejectRedirectAndPermanentFailureWithoutForwardingCredential() {
        for(int status:List.of(302,401,404)){
            var pending=swaps.request(command(pair()),2,"test");receiver.status.set(status);notifications.dispatchDue();var failed=only(pending.id());
            assertThat(failed.status()).isEqualTo("FAILED");assertThat(failed.attempts()).isEqualTo(1);assertThat(failed.lastHttpStatus()).isEqualTo(status);
        }assertThat(receiver.redirectRequests.get()).isZero();
    }
    @Test void shouldSerializeConcurrentDispatchersWithoutDuplicateLiveClaim() throws Exception {
        var pending=swaps.request(command(pair()),2,"test");receiver.block=true;var pool=Executors.newSingleThreadExecutor();
        try{var first=pool.submit(notifications::dispatchDue);assertThat(receiver.entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(notifications.dispatchDue()).isZero();receiver.release.countDown();assertThat(first.get(5,TimeUnit.SECONDS)).isEqualTo(1);}
        finally{receiver.release.countDown();pool.shutdownNow();}
        assertThat(receiver.bodies).hasSize(1);assertThat(only(pending.id()).status()).isEqualTo("DELIVERED");
    }
    @Test void shouldFenceExpiredOldReceiptAndReuseSameFrozenKey() throws Exception {
        var pending=swaps.request(command(pair()),2,"test");var pool=Executors.newSingleThreadExecutor();receiver.block=true;
        try{var old=pool.submit(notifications::dispatchDue);assertThat(receiver.entered.await(5,TimeUnit.SECONDS)).isTrue();var id=only(pending.id()).id();
            jdbc.sql("UPDATE oncall_swap_notification SET lease_until=:past WHERE id=:id").param("past",now().minusSeconds(1)).param("id",id).update();receiver.block=false;receiver.status.set(503);assertThat(notifications.dispatchDue()).isEqualTo(1);
            assertThat(only(pending.id()).status()).isEqualTo("PENDING");receiver.release.countDown();assertThat(old.get(5,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(only(pending.id()).status()).isEqualTo("PENDING");assertThat(only(pending.id()).lastHttpStatus()).isEqualTo(503);assertThat(receiver.keys).hasSize(2);assertThat(receiver.keys.get(0)).isEqualTo(receiver.keys.get(1));}
        finally{receiver.release.countDown();pool.shutdownNow();}
    }
    @Test void shouldStopReclaimingExhaustedExpiredLeaseWithoutAnotherHttp() {
        var pending=swaps.request(command(pair()),2,"test");var id=only(pending.id()).id();jdbc.sql("UPDATE oncall_swap_notification SET status='CLAIMED',attempts=3,lease_token=:key,lease_until=:past WHERE id=:id")
                .param("id",id).param("key",UUID.randomUUID().toString()).param("past",now().minusSeconds(1)).update();
        assertThat(notifications.dispatchDue()).isEqualTo(1);assertThat(only(pending.id()).status()).isEqualTo("FAILED");assertThat(only(pending.id()).lastErrorCode()).isEqualTo("LEASE_EXPIRED");assertThat(receiver.bodies).isEmpty();
    }
    @Test void shouldManuallyRetryOriginalVersionAndAcknowledgeLostReceiptWithoutNewAudit() {
        var pending=swaps.request(command(pair()),2,"test");receiver.status.set(401);notifications.dispatchDue();var failed=only(pending.id());long before=auditCount();
        var retried=notifications.retry(pending.id(),failed.id(),failed.version(),"人工核对后重新投递",2,"test");assertThat(retried.status()).isEqualTo("PENDING");
        assertThat(notifications.retry(pending.id(),failed.id(),failed.version(),"人工核对后重新投递",2,"test")).isEqualTo(retried);assertThat(auditCount()).isEqualTo(before+1);
        assertThatThrownBy(()->notifications.retry(pending.id(),failed.id(),failed.version(),"改内容",2,"test")).isInstanceOf(ApiException.class);receiver.status.set(204);notifications.dispatchDue();
        assertThat(notifications.retry(pending.id(),failed.id(),failed.version(),"人工核对后重新投递",2,"test").status()).isEqualTo("DELIVERED");assertThat(only(pending.id()).totalAttempts()).isEqualTo(2);assertThat(swaps.get(pending.id()).status()).isEqualTo("PENDING");
    }
    @Test void shouldRollbackManualRetryWhenAuditFails() {
        var pending=swaps.request(command(pair()),2,"test");receiver.status.set(401);notifications.dispatchDue();var failed=only(pending.id());long before=auditCount();
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("controlled retry audit failure");}).when(audit).recordAs(anyLong(),anyString(),eq("ONCALL_SWAP_NOTIFICATION_RETRY"),anyString(),any(),anyString());
        assertThatThrownBy(()->notifications.retry(pending.id(),failed.id(),failed.version(),"重试",1,"test")).isInstanceOf(IllegalStateException.class);
        assertThat(only(pending.id())).isEqualTo(failed);assertThat(auditCount()).isEqualTo(before);
    }
    @Test void shouldEnforceAuthenticatedHttpRolesAndExplicitRetryVersion() throws Exception {
        var pending=swaps.request(command(pair()),2,"test");receiver.status.set(401);notifications.dispatchDue();var failed=only(pending.id());String route="/api/v1/on-call/swaps/"+pending.id()+"/notifications",retry=route+"/"+failed.id()+"/retry";
        mvc.perform(get(route)).andExpect(status().isUnauthorized());String admin=login("admin"),auditor=login("auditor"),owner=login("zhangwei");
        mvc.perform(get(route).header("Authorization",auditor)).andExpect(status().isOk()).andExpect(jsonPath("$.data.deliveries[0].payloadJson").doesNotExist());
        mvc.perform(post(retry).header("Authorization",auditor).contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+failed.version()+",\"reason\":\"重试\"}")).andExpect(status().isForbidden());
        mvc.perform(post(retry).header("Authorization",admin).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"缺版本\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(retry).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+failed.version()+",\"reason\":\"本人重试\"}")).andExpect(status().isOk());
        assertThat(swaps.get(pending.id()).status()).isEqualTo("PENDING");
    }
    @Test void shouldRejectRetryForForeignSwapIneligibleActorOrSupersededEvent() {
        var pending=swaps.request(command(pair()),2,"test");receiver.status.set(401);notifications.dispatchDue();var failed=only(pending.id());
        assertThatThrownBy(()->notifications.retry(pending.id(),failed.id(),failed.version(),"重试",4,"test")).isInstanceOf(ApiException.class);
        var other=swaps.request(command(pair()),2,"test");assertThatThrownBy(()->notifications.retry(other.id(),failed.id(),failed.version(),"重试",2,"test")).isInstanceOf(ApiException.class);
        swaps.decide(pending.id(),decision("WITHDRAWN"),2,"test");assertThatThrownBy(()->notifications.retry(pending.id(),failed.id(),failed.version(),"重试",2,"test")).isInstanceOf(ApiException.class);
    }
    @Test void shouldPreventExternalDeliveryFromInsideBusinessTransactionAndPreserveDisabledHistory() {
        var pending=swaps.request(command(pair()),2,"test");assertThatThrownBy(()->transactions.execute(s->{notifications.dispatchDue();return null;})).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        var disabled=new OnCallSwapNotifications(jdbc,json,audit,new OnCallSwapNotificationProperties(false,null,null,null,null,null,null,null,0,0,false,30));
        transactions.execute(s->{disabled.enqueue(pending);return null;});assertThat(disabled.dispatchDue()).isZero();assertThat(disabled.list(pending.id()).enabled()).isFalse();assertThat(disabled.list(pending.id()).deliveries()).hasSize(1);assertThat(receiver.bodies).isEmpty();
    }

    @Test void shouldRejectNewManualRetryAfterPayloadRetentionWithoutChangingHistory() {
        var pending=swaps.request(command(pair()),2,"test");receiver.status.set(401);notifications.dispatchDue();var failed=only(pending.id());long count=auditCount();
        jdbc.sql("UPDATE oncall_swap_notification SET payload_expires_at=:past WHERE id=:id").param("past",now().minusSeconds(1)).param("id",failed.id()).update();
        assertThatThrownBy(()->notifications.retry(pending.id(),failed.id(),failed.version(),"过期后新重试",2,"test"))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ONCALL_SWAP_NOTIFICATION_PAYLOAD_EXPIRED"));
        assertThat(only(pending.id()).status()).isEqualTo("FAILED");assertThat(auditCount()).isEqualTo(count);assertThat(swaps.get(pending.id()).status()).isEqualTo("PENDING");
        assertThat(receiver.bodies).hasSize(1);
    }

    @Test void shouldRunOneUnqueuedWorkerWithoutBlockingSharedSchedulerTicks() throws Exception {
        var pending=swaps.request(command(pair()),2,"test");receiver.block=true;job.tick();assertThat(receiver.entered.await(5,TimeUnit.SECONDS)).isTrue();
        long at=System.nanoTime();for(int i=0;i<10;i++)job.tick();assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at)).isLessThan(1000);
        assertThat(receiver.bodies).hasSize(1);receiver.release.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!only(pending.id()).status().equals("DELIVERED")&&System.nanoTime()<deadline)Thread.sleep(20);
        assertThat(only(pending.id()).status()).isEqualTo("DELIVERED");assertThat(receiver.bodies).hasSize(1);
    }

    @Test void shouldEraseDeliveredPayloadButPreserveTechnicalReceiptAndOriginalRetryAcknowledgement() {
        var pending=swaps.request(command(pair()),2,"test");receiver.status.set(401);notifications.dispatchDue();var failed=only(pending.id());
        String reason="原人工重试";notifications.retry(pending.id(),failed.id(),failed.version(),reason,2,"test");receiver.status.set(204);notifications.dispatchDue();
        var delivered=only(pending.id());long count=auditCount();expire(delivered.id());
        assertThat(notifications.purgeExpiredPayloads()).isEqualTo(1);var erased=only(pending.id());
        assertThat(payload(erased.id())).isEmpty();assertThat(erased.payloadErasedAt()).isNotNull();assertThat(erased.status()).isEqualTo("DELIVERED");
        assertThat(erased.deliveredAt()).isEqualTo(delivered.deliveredAt());assertThat(erased.deliveryKey()).isEqualTo(delivered.deliveryKey());
        assertThat(erased.totalAttempts()).isEqualTo(delivered.totalAttempts());assertThat(erased.lastHttpStatus()).isEqualTo(204);assertThat(auditCount()).isEqualTo(count);
        assertThat(notifications.retry(pending.id(),failed.id(),failed.version(),reason,2,"test")).isEqualTo(erased);
        assertThat(notifications.purgeExpiredPayloads()).isZero();assertThat(notifications.dispatchDue()).isZero();assertThat(receiver.bodies).hasSize(2);
        assertThat(swaps.get(pending.id()).status()).isEqualTo("PENDING");
    }
    @Test void shouldBlockExpiredClaimsBeforeBoundedCleanupAndKeepNotificationHistory() {
        var rows=new ArrayList<OnCallSwapNotifications.View>();for(int i=0;i<3;i++){var pending=swaps.request(command(pair()),2,"test");rows.add(only(pending.id()));expire(rows.get(i).id());}
        long count=auditCount();var bounded=policy(true,true,1);
        assertThat(bounded.dispatchDue()).isZero();assertThat(receiver.bodies).isEmpty();
        for(int i=0;i<3;i++)assertThat(bounded.purgeExpiredPayloads()).isEqualTo(1);
        assertThat(bounded.purgeExpiredPayloads()).isZero();
        for(var old:rows){var row=only(old.swapId());assertThat(row.status()).isEqualTo("SKIPPED");assertThat(row.lastErrorCode()).isEqualTo("RETENTION_EXPIRED");
            assertThat(row.totalAttempts()).isZero();assertThat(payload(row.id())).isEmpty();assertThat(swaps.get(row.swapId()).status()).isEqualTo("PENDING");}
        assertThat(auditCount()).isEqualTo(count);
    }
    @Test void shouldDeferErasureWhileValidLeaseIsInFlightThenPreserveItsReceipt() throws Exception {
        var pending=swaps.request(command(pair()),2,"test");receiver.block=true;var pool=Executors.newSingleThreadExecutor();
        try{var active=pool.submit(notifications::dispatchDue);assertThat(receiver.entered.await(5,TimeUnit.SECONDS)).isTrue();expire(only(pending.id()).id());
            assertThat(notifications.purgeExpiredPayloads()).isZero();assertThat(payload(only(pending.id()).id())).isNotEmpty();assertThat(notifications.dispatchDue()).isZero();
            receiver.release.countDown();assertThat(active.get(5,TimeUnit.SECONDS)).isEqualTo(1);assertThat(only(pending.id()).status()).isEqualTo("DELIVERED");
            assertThat(notifications.purgeExpiredPayloads()).isEqualTo(1);assertThat(only(pending.id()).status()).isEqualTo("DELIVERED");assertThat(receiver.bodies).hasSize(1);
        }finally{receiver.release.countDown();pool.shutdownNow();}
    }
    @Test void shouldEraseExpiredLeaseWithoutHttpAndFenceItsLateReceipt() throws Exception {
        var pending=swaps.request(command(pair()),2,"test");receiver.block=true;var pool=Executors.newSingleThreadExecutor();
        try{var old=pool.submit(notifications::dispatchDue);assertThat(receiver.entered.await(5,TimeUnit.SECONDS)).isTrue();long id=only(pending.id()).id();expire(id);
            jdbc.sql("UPDATE oncall_swap_notification SET lease_until=:past WHERE id=:id").param("past",now().minusSeconds(1)).param("id",id).update();
            assertThat(notifications.dispatchDue()).isZero();assertThat(notifications.purgeExpiredPayloads()).isEqualTo(1);var erased=only(pending.id());
            receiver.release.countDown();assertThat(old.get(5,TimeUnit.SECONDS)).isEqualTo(1);assertThat(only(pending.id())).isEqualTo(erased);
            assertThat(erased.status()).isEqualTo("SKIPPED");assertThat(erased.lastErrorCode()).isEqualTo("RETENTION_EXPIRED");assertThat(erased.leaseUntil()).isNull();
            assertThat(payload(id)).isEmpty();assertThat(receiver.bodies).hasSize(1);
        }finally{receiver.release.countDown();pool.shutdownNow();}
    }
    @Test void shouldAllowOptOutButNeverReviveErasedPayloadAfterDisablingRetention() {
        var pending=swaps.request(command(pair()),2,"test");long id=only(pending.id()).id();expire(id);var disabled=policy(true,false,20);
        assertThat(disabled.purgeExpiredPayloads()).isZero();assertThat(disabled.dispatchDue()).isEqualTo(1);assertThat(only(pending.id()).status()).isEqualTo("DELIVERED");
        assertThat(notifications.purgeExpiredPayloads()).isEqualTo(1);assertThat(disabled.dispatchDue()).isZero();
        assertThatThrownBy(()->disabled.retry(pending.id(),id,only(pending.id()).version(),"不可恢复空载荷",2,"test"))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ONCALL_SWAP_NOTIFICATION_PAYLOAD_EXPIRED"));
        assertThat(payload(id)).isEmpty();assertThat(receiver.bodies).hasSize(1);
    }
    @Test void shouldCleanWithSendingDisabledWithoutOccupyingSharedScheduler() throws Exception {
        var pending=swaps.request(command(pair()),2,"test");expire(only(pending.id()).id());var disabled=policy(false,true,1);
        var cleanupJob=new OnCallSwapNotificationJob(disabled,policyProperties(false,true,1));try{cleanupJob.tick();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(only(pending.id()).payloadErasedAt()==null&&System.nanoTime()<deadline)Thread.sleep(20);
            assertThat(only(pending.id()).payloadErasedAt()).isNotNull();assertThat(disabled.list(pending.id()).enabled()).isFalse();
            assertThat(disabled.list(pending.id()).retentionEnabled()).isTrue();assertThat(disabled.dispatchDue()).isZero();assertThat(receiver.bodies).isEmpty();
        }finally{cleanupJob.destroy();}
        assertThatThrownBy(()->transactions.execute(s->{notifications.purgeExpiredPayloads();return null;})).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    @Test void shouldSerializeConcurrentCleanupWithoutDoubleErasureOrAuditChanges() throws Exception {
        var pending=swaps.request(command(pair()),2,"test");long id=only(pending.id()).id();expire(id);long count=auditCount();var pool=Executors.newFixedThreadPool(2);
        var barrier=new CyclicBarrier(2);try{var a=pool.submit(()->{barrier.await();return notifications.purgeExpiredPayloads();});var b=pool.submit(()->{barrier.await();return notifications.purgeExpiredPayloads();});
            assertThat(a.get(5,TimeUnit.SECONDS)+b.get(5,TimeUnit.SECONDS)).isEqualTo(1);
        }finally{pool.shutdownNow();}assertThat(only(pending.id()).version()).isEqualTo(1);assertThat(auditCount()).isEqualTo(count);assertThat(payload(id)).isEmpty();assertThat(receiver.bodies).isEmpty();
    }
    @Test void shouldFreezeDatabaseDeadlineAcrossRetryAndRejectUnsafeRetentionConfiguration() {
        LocalDateTime before=now();var pending=swaps.request(command(pair()),2,"test");var original=only(pending.id());LocalDateTime after=now();
        assertThat(original.payloadExpiresAt()).isBetween(before.plusDays(30),after.plusDays(30));receiver.status.set(401);notifications.dispatchDue();var failed=only(pending.id());
        notifications.retry(pending.id(),failed.id(),failed.version(),"原期限不延长",2,"test");assertThat(only(pending.id()).payloadExpiresAt()).isEqualTo(original.payloadExpiresAt());
        for(int days:List.of(0,-1,3651))assertThatThrownBy(()->new OnCallSwapNotifications(jdbc,json,audit,
                new OnCallSwapNotificationProperties(false,null,null,null,null,null,null,null,0,20,true,days))).isInstanceOf(IllegalArgumentException.class);
        for(int batch:List.of(0,101))assertThatThrownBy(()->policy(false,true,batch)).isInstanceOf(IllegalArgumentException.class);
    }
    OnCallSwapNotificationProperties policyProperties(boolean sending,boolean retention,int batch){return new OnCallSwapNotificationProperties(sending,properties.url(),properties.token(),
            properties.connectTimeout(),properties.readTimeout(),properties.lease(),properties.retryBaseDelay(),properties.retryMaxDelay(),properties.maxAttempts(),batch,retention,properties.payloadRetentionDays());}
    OnCallSwapNotifications policy(boolean sending,boolean retention,int batch){return new OnCallSwapNotifications(jdbc,json,audit,policyProperties(sending,retention,batch));}
    void expire(long id){jdbc.sql("UPDATE oncall_swap_notification SET payload_expires_at=CURRENT_TIMESTAMP(6) WHERE id=:id").param("id",id).update();}
    String payload(long id){return jdbc.sql("SELECT payload_json FROM oncall_swap_notification WHERE id=:id").param("id",id).query(String.class).single();}

    Fixture pair(){long resource=insert(jdbc.sql("INSERT INTO cmdb_resource(resource_code,resource_type,name,environment,status) VALUES (:code,'APPLICATION','通知独立服务','TEST','RUNNING')").param("code","NOTIFY-"+UUID.randomUUID()));long schedule=insert(jdbc.sql("INSERT INTO oncall_schedule(service_resource_id,name) VALUES (:id,'通知计划')").param("id",resource));PlanMembershipFixtures.grant(jdbc,schedule,1,2,3);LocalDateTime at=now().plusDays(3).withNano(0);
        return new Fixture(roster.create(new OnCallRosterService.ShiftCommand(schedule,2,at,at.plusHours(2),false,"通知本人班次"),1L,"test"),roster.create(new OnCallRosterService.ShiftCommand(schedule,3,at.plusDays(1),at.plusDays(1).plusHours(2),false,"通知对方班次"),1L,"test"));}
    OnCallSwapService.Command command(Fixture f){return new OnCallSwapService.Command(f.first().id(),0,f.second().id(),0,UUID.randomUUID().toString(),"不应出站的用户说明 sensitive-input-sentinel");}
    OnCallSwapService.Decision decision(String status){return new OnCallSwapService.Decision(0,status,"双方实际决定");}
    OnCallSwapNotifications.View only(long id){return notifications.list(id).deliveries().get(0);}
    long insert(JdbcClient.StatementSpec s){var holder=new GeneratedKeyHolder();s.update(holder,"id");return holder.getKey().longValue();}
    LocalDateTime now(){return jdbc.sql("SELECT CURRENT_TIMESTAMP(6)").query((rs,n)->rs.getObject(1,LocalDateTime.class)).single();}
    void due(long id){jdbc.sql("UPDATE oncall_swap_notification SET next_attempt_at=:past WHERE id=:id").param("id",id).param("past",now().minusSeconds(1)).update();}
    long shifts(Fixture f){return jdbc.sql("SELECT COUNT(*) FROM oncall_shift WHERE schedule_id=:id").param("id",f.first().scheduleId()).query(Long.class).single();}
    long swapsCount(){return jdbc.sql("SELECT COUNT(*) FROM oncall_shift_swap").query(Long.class).single();}
    long auditCount(){return jdbc.sql("SELECT COUNT(*) FROM audit_log").query(Long.class).single();}
    String jsonString(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new AssertionError(e);}}
    com.fasterxml.jackson.databind.JsonNode jsonTree(String value){try{return json.readTree(value);}catch(Exception e){throw new AssertionError(e);}}
    String login(String username)throws Exception{return "Bearer "+json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\""+username+"\",\"password\":\"OpsPilot@2026\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("accessToken").asText();}
    record Fixture(OnCallRosterService.ShiftView first,OnCallRosterService.ShiftView second){}
    record UserState(long id,String name,String status,String role){}
    static class Receiver {
        final HttpServer server;final ExecutorService pool=Executors.newFixedThreadPool(4);final AtomicInteger status=new AtomicInteger(204),redirectRequests=new AtomicInteger();
        final List<String> bodies=new CopyOnWriteArrayList<>(),keys=new CopyOnWriteArrayList<>();final List<Boolean> authCorrect=new CopyOnWriteArrayList<>();
        volatile boolean block;volatile CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        Receiver(){try{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(pool);server.createContext("/notify",exchange->{
            int response=status.get();bodies.add(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));authCorrect.add("Bearer cp87-notification-token".equals(exchange.getRequestHeaders().getFirst("Authorization")));
            if(block){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            if(response==302)exchange.getResponseHeaders().add("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/redirect");
            byte[] body="sensitive-receiver-sentinel".getBytes(java.nio.charset.StandardCharsets.UTF_8);try{exchange.sendResponseHeaders(response,response==204?-1:body.length);if(response!=204)exchange.getResponseBody().write(body);}finally{exchange.close();}});
            server.createContext("/redirect",exchange->{redirectRequests.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});server.start();}
            catch(java.io.IOException e){throw new IllegalStateException(e);}}
        void reset(){block=false;release.countDown();entered=new CountDownLatch(1);release=new CountDownLatch(1);status.set(204);bodies.clear();keys.clear();authCorrect.clear();redirectRequests.set(0);}
    }
}
