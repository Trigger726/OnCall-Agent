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
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.trigger.opspilot.common.ApiException;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual receiver HTTP and production services on the same owned H2/MySQL scenarios. No mocked transport. */
abstract class OpenNotificationScenarios {
    @Autowired JdbcClient jdbc;
    @Autowired DataSource datasource;
    @Autowired ObjectMapper json;
    @Autowired OnCallOpenHandoffService open;
    @Autowired OnCallOpenHandoffRecipients recipients;
    @Autowired OnCallPlanMembershipService members;
    @Autowired OnCallRosterService roster;
    @Autowired OnCallOpenNotificationProperties properties;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired MockMvc mvc;
    @Autowired OnCallOpenNotificationRedrive redrive;
    @SpyBean org.trigger.opspilot.audit.AuditService audit;
    @SpyBean OnCallOpenNotifications notifications;
    private static HttpServer receiver;
    private static ExecutorService httpWorkers;
    private static final List<Received> received=new CopyOnWriteArrayList<>();
    private static final AtomicInteger code=new AtomicInteger(200);
    private static volatile CountDownLatch arrived,release;
    private static volatile boolean blockFirst;
    private final List<Long> ownedHandoffs=new ArrayList<>();
    private record Received(String key,String payload,boolean authenticated) {}

    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) throws Exception {
        receiver=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);httpWorkers=Executors.newFixedThreadPool(4);receiver.setExecutor(httpWorkers);
        receiver.createContext("/deliver",exchange->{
            int status=code.get();var body=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestHeaders().getFirst("Idempotency-Key"),body,"Bearer test-open-channel".equals(exchange.getRequestHeaders().getFirst("Authorization"))));
            if(blockFirst&&received.size()==1){arrived.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            if(status==302)exchange.getResponseHeaders().add("Location","http://127.0.0.1:"+receiver.getAddress().getPort()+"/redirected");
            try{exchange.sendResponseHeaders(status,-1);}finally{exchange.close();}
        });
        receiver.createContext("/redirected",exchange->{received.add(new Received("REDIRECT","",false));exchange.sendResponseHeaders(200,-1);exchange.close();});receiver.start();
        registry.add("opspilot.oncall.open.notification.enabled",()->true);
        registry.add("opspilot.oncall.open.notification.url",()->"http://127.0.0.1:"+receiver.getAddress().getPort()+"/deliver");
        registry.add("opspilot.oncall.open.notification.token",()->"test-open-channel");
        registry.add("opspilot.oncall.open.notification.connect-timeout",()->"200ms");
        registry.add("opspilot.oncall.open.notification.read-timeout",()->"2s");
        registry.add("opspilot.oncall.open.notification.lease",()->"5s");
        registry.add("opspilot.oncall.open.notification.retry-base-delay",()->"100ms");
        registry.add("opspilot.oncall.open.notification.retry-max-delay",()->"1s");
        registry.add("opspilot.oncall.open.notification.max-attempts",()->2);
        registry.add("opspilot.oncall.open.notification.batch-size",()->20);
        registry.add("ONCALL_OPEN_NOTIFICATION_DISPATCH_INITIAL_DELAY",()->3600000);
    }
    @AfterAll static void stopReceiver() throws Exception {receiver.stop(0);httpWorkers.shutdownNow();assertThat(httpWorkers.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    @BeforeEach void identity(TestInfo info) throws Exception {
        jdbc.sql("UPDATE oncall_open_handoff_notification SET status='SKIPPED',lease_token=NULL,lease_until=NULL WHERE status IN ('PENDING','CLAIMED')").update();
        received.clear();code.set(200);blockFirst=false;arrived=new CountDownLatch(1);release=new CountDownLatch(1);
        ownedHandoffs.clear();
        try(var connection=datasource.getConnection()) {
            var meta=connection.getMetaData();if(getClass().getSimpleName().startsWith("MySql")){assertThat(meta.getDatabaseProductName()).isEqualTo("MySQL");assertThat(meta.getDatabaseProductVersion()).startsWith("8.4.");assertThat(connection.getCatalog()).isEqualTo("opspilot_open_notification_test");}
            assertThat(count("flyway_schema_history","success=TRUE AND version IS NOT NULL")).isEqualTo(42);
            assertThat(count("flyway_schema_history","success=TRUE AND version='41'")).isEqualTo(1);
            assertThat(count("flyway_schema_history","success=TRUE AND version='42'")).isEqualTo(1);
            System.out.println("OPEN_NOTIFICATION_DATABASE "+json.writeValueAsString(Map.of("case",info.getTestMethod().orElseThrow().getName(),"product",meta.getDatabaseProductName(),"version",meta.getDatabaseProductVersion(),"schema",connection.getCatalog(),"migration41",true,"migration42",true)));
        }
    }
    @AfterEach void cleanup(TestInfo info) throws Exception {
        release.countDown();reset(AopTestUtils.<OnCallOpenNotifications>getUltimateTargetObject(notifications));reset(AopTestUtils.<org.trigger.opspilot.audit.AuditService>getUltimateTargetObject(audit));
        var events=ownedHandoffs.stream().flatMap(id->notifications.list(id).deliveries().stream()).toList();
        var redrives=events.isEmpty()?List.<RedriveFact>of():jdbc.sql("SELECT * FROM oncall_open_notification_redrive WHERE notification_id IN (:ids) ORDER BY id")
                .param("ids",events.stream().map(OnCallOpenNotifications.View::id).toList()).query((rs,n)->new RedriveFact(rs.getLong("notification_id"),rs.getLong("actor_id"),rs.getString("operation_key"),rs.getInt("captured_version"),rs.getInt("queued_version"),rs.getInt("prior_attempts"),rs.getObject("prior_http_status",Integer.class),rs.getString("prior_error_code"),rs.getString("delivery_key"))).list();
        long redriveAudits=events.isEmpty()?0:jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action='ONCALL_OPEN_NOTIFICATION_REDRIVE' AND target_id IN (:ids)")
                .param("ids",events.stream().map(e->Long.toString(e.id())).toList()).query(Long.class).single();
        var digests=new ArrayList<String>();for(var request:received)digests.add(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(request.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        System.out.println("OPEN_NOTIFICATION_OUTCOME "+json.writeValueAsString(Map.of("case",info.getTestMethod().orElseThrow().getName(),
                "httpCalls",received.size(),"authenticatedRequests",received.stream().filter(Received::authenticated).count(),"redriveReceipts",redrives,"redriveAudits",redriveAudits,
                "keys",received.stream().map(Received::key).toList(),"payloadSha256",digests,"events",events)));
    }

    @Test void shouldDeliverOnlyCapturedRespondersWithStableKeyAndNoAutomaticClaim() throws Exception {
        var f=fixture(2);var row=publish(f);var saved=recipients.get(row.id()).publication();
        assertThat(notifications.list(row.id()).deliveries()).extracting(OnCallOpenNotifications.View::recipientId).containsExactlyElementsOf(f.targets());
        assertThat(notifications.dispatchDue()).isEqualTo(2);assertThat(received).hasSize(2).allSatisfy(r->assertThat(r.authenticated()).isTrue());
        for(var response:received){var payload=json.readTree(response.payload());assertThat(payload.path("eventType").asText()).isEqualTo("ONCALL_OPEN_PUBLISHED");
            assertThat(payload.path("humanConfirmationRequired").asBoolean()).isTrue();assertThat(payload.path("currentCoverageMustBeReadSeparately").asBoolean()).isTrue();
            assertThat(payload.path("recipientMemberVersion").asInt()).isZero();assertThat(payload.path("recipientId").asLong()).isNotEqualTo(f.owner());
            assertThat(response.payload()).doesNotContain("requestKey","reason","operationKey","test-open-channel");}
        assertThat(notifications.list(row.id()).deliveries()).allSatisfy(v->{assertThat(v.status()).isEqualTo("DELIVERED");assertThat(v.lastHttpStatus()).isEqualTo(200);assertThat(v.attempts()).isEqualTo(1);});
        assertThat(open.get(row.id()).status()).isEqualTo("OPEN");assertThat(open.coverage(row.id()).replacement()).isNull();assertThat(recipients.get(row.id()).publication()).isEqualTo(saved);
    }
    @Test void shouldKeepOriginalAcknowledgementReadOnlyBeforeAndAfterDelivery() {
        var f=fixture(1);var command=command(f);var row=open.request(command,f.owner(),"test");ownedHandoffs.add(row.id());var before=notifications.list(row.id()).deliveries();
        open.request(command,f.owner(),"test");assertThat(notifications.list(row.id()).deliveries()).isEqualTo(before);
        notifications.dispatchDue();var delivered=notifications.list(row.id()).deliveries();open.request(command,f.owner(),"test");assertThat(notifications.list(row.id()).deliveries()).isEqualTo(delivered);assertThat(notifications.dispatchDue()).isZero();assertThat(received).hasSize(1);
    }
    @Test void shouldSkipRevokedOriginalRecipientAndNeverRetargetLaterGrants() {
        var f=fixture(2);var row=publish(f);revoke(f.plan(),f.targets().get(0));grant(f.plan(),user("later"));notifications.dispatchDue();
        assertThat(received).hasSize(1);assertThat(notifications.list(row.id()).deliveries()).extracting(OnCallOpenNotifications.View::status).containsExactly("SKIPPED","DELIVERED");
    }
    @Test void shouldSkipDisabledRecipientAccountWithoutChangingFrozenPayload() {
        var f=fixture(1);var row=publish(f);String saved=payload(row.id());jdbc.sql("UPDATE sys_user SET status='DISABLED' WHERE id=:id").param("id",f.targets().get(0)).update();assertSkipped(row.id());assertThat(payload(row.id())).isEqualTo(saved);
    }
    @Test void shouldSkipWhenPublisherResponsePermissionIsRevoked() {var f=fixture(1);var row=publish(f);revoke(f.plan(),f.owner());assertSkipped(row.id());}
    @Test void shouldSkipInactivePlan() {var f=fixture(1);var row=publish(f);jdbc.sql("UPDATE oncall_schedule SET active=FALSE WHERE id=:id").param("id",f.plan()).update();assertSkipped(row.id());}
    @Test void shouldSkipCancelledSource() {var f=fixture(1);var row=publish(f);roster.cancelManaged(f.source().id(),0,"explicit source cancellation",1,"test");assertSkipped(row.id());}
    @Test void shouldSkipAfterActualClaimWithoutRewritingCoverageOrPublication() {var f=fixture(1);var row=publish(f);var coverage=open.claim(row.id(),operation(),f.targets().get(0),"test");assertSkipped(row.id());assertThat(open.coverage(row.id()).replacement()).isEqualTo(coverage.replacement());}
    @Test void shouldSkipAfterActualWithdrawalAndPreserveOriginalEvent() {var f=fixture(1);var row=publish(f);open.withdraw(row.id(),operation(),f.owner(),"test");assertSkipped(row.id());assertThat(notifications.list(row.id()).deliveries().get(0).eventVersion()).isZero();}
    @Test void shouldPersistEmptyAudienceWithoutLaterGrantBackfill() {var f=fixture(0);var command=command(f);var row=open.request(command,f.owner(),"test");ownedHandoffs.add(row.id());grant(f.plan(),user("later"));open.request(command,f.owner(),"test");assertThat(notifications.list(row.id()).deliveries()).isEmpty();assertThat(notifications.dispatchDue()).isZero();assertThat(received).isEmpty();}
    @Test void shouldRetryTransient503WithSameFrozenPayloadAndIdempotencyKey() {
        var f=fixture(1);var row=publish(f);code.set(503);notifications.dispatchDue();assertThat(delivery(row.id()).status()).isEqualTo("PENDING");
        assertThat(delivery(row.id()).lastErrorCode()).isEqualTo("HTTP_503");due(row.id());code.set(200);notifications.dispatchDue();assertThat(received).hasSize(2);assertThat(received.get(1)).isEqualTo(received.get(0));assertThat(delivery(row.id()).status()).isEqualTo("DELIVERED");assertThat(delivery(row.id()).attempts()).isEqualTo(2);
    }
    @Test void shouldBound429RetriesAndNeverAutomaticallyRetryFailedEvents() {var row=publish(fixture(1));code.set(429);notifications.dispatchDue();due(row.id());notifications.dispatchDue();assertThat(delivery(row.id()).status()).isEqualTo("FAILED");assertThat(delivery(row.id()).attempts()).isEqualTo(2);assertThat(notifications.dispatchDue()).isZero();assertThat(received).hasSize(2);}
    @Test void shouldNotFollowRedirectsOrLeakChannelToken() {var row=publish(fixture(1));code.set(302);notifications.dispatchDue();assertThat(delivery(row.id()).status()).isEqualTo("FAILED");assertThat(delivery(row.id()).lastHttpStatus()).isEqualTo(302);assertThat(received).hasSize(1);}
    @Test void shouldFenceLateOldLeaseReceiptAfterAnotherWorkerSucceeds() throws Exception {
        var row=publish(fixture(1));blockFirst=true;code.set(503);var worker=Executors.newSingleThreadExecutor();
        try{var old=worker.submit(notifications::dispatchDue);assertThat(arrived.await(3,TimeUnit.SECONDS)).isTrue();
            jdbc.sql("UPDATE oncall_open_handoff_notification SET lease_until=:now WHERE handoff_id=:id").param("now",now().minusSeconds(1)).param("id",row.id()).update();
            code.set(200);assertThat(notifications.dispatchDue()).isEqualTo(1);var committed=delivery(row.id());assertThat(committed.status()).isEqualTo("DELIVERED");
            release.countDown();assertThat(old.get(4,TimeUnit.SECONDS)).isEqualTo(1);assertThat(delivery(row.id())).isEqualTo(committed);assertThat(received).hasSize(2);assertThat(received.get(1).key()).isEqualTo(received.get(0).key());
        }finally{release.countDown();worker.shutdownNow();assertThat(worker.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void shouldRefuseNetworkDispatchInsideCallerTransaction() {var row=publish(fixture(1));var tx=new TransactionTemplate(transactionManager);assertThatThrownBy(()->tx.execute(s->notifications.dispatchDue())).isInstanceOf(IllegalTransactionStateException.class);assertThat(delivery(row.id()).status()).isEqualTo("PENDING");assertThat(received).isEmpty();}
    @Test void shouldBoundTransportTimeoutWithoutClaimingDeliveryOrHumanAcceptance() throws Exception {
        var row=publish(fixture(1));blockFirst=true;var worker=Executors.newSingleThreadExecutor();
        try{var result=worker.submit(notifications::dispatchDue);assertThat(arrived.await(3,TimeUnit.SECONDS)).isTrue();assertThat(result.get(4,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(delivery(row.id()).status()).isEqualTo("PENDING");assertThat(delivery(row.id()).lastErrorCode()).isEqualTo("TRANSPORT_ERROR");assertThat(delivery(row.id()).deliveredAt()).isNull();assertThat(open.get(row.id()).status()).isEqualTo("OPEN");
        }finally{release.countDown();worker.shutdownNow();assertThat(worker.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void shouldFinalizeExpiredMaximumLeaseWithoutSendingAgain() {
        var row=publish(fixture(1));jdbc.sql("UPDATE oncall_open_handoff_notification SET status='CLAIMED',attempts=2,lease_token=:token,lease_until=:now WHERE handoff_id=:id")
                .param("id",row.id()).param("token",UUID.randomUUID().toString()).param("now",now().minusSeconds(1)).update();
        assertThat(notifications.dispatchDue()).isEqualTo(1);assertThat(delivery(row.id()).status()).isEqualTo("FAILED");assertThat(delivery(row.id()).lastErrorCode()).isEqualTo("LEASE_EXPIRED");assertThat(received).isEmpty();
    }
    @Test void shouldFinalizePendingAttemptsAfterConfigurationLimitIsTightened() {
        var row=publish(fixture(1));jdbc.sql("UPDATE oncall_open_handoff_notification SET attempts=2,next_attempt_at=:now WHERE handoff_id=:id AND status='PENDING'")
                .param("id",row.id()).param("now",now().minusSeconds(1)).update();
        assertThat(notifications.dispatchDue()).isEqualTo(1);assertThat(delivery(row.id()).status()).isEqualTo("FAILED");assertThat(delivery(row.id()).lastErrorCode()).isEqualTo("ATTEMPTS_EXHAUSTED");assertThat(notifications.dispatchDue()).isZero();assertThat(received).isEmpty();
    }
    @Test void shouldDropBusyWorkerTicksWithoutInMemoryBacklog() throws Exception {
        var row=publish(fixture(1));blockFirst=true;var job=new OnCallOpenNotificationJob(notifications,properties);
        try{job.tick();assertThat(arrived.await(3,TimeUnit.SECONDS)).isTrue();for(int i=0;i<100;i++)job.tick();assertThat(received).hasSize(1);
            release.countDown();var deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!delivery(row.id()).status().equals("DELIVERED")&&System.nanoTime()<deadline)Thread.sleep(10);
            assertThat(delivery(row.id()).status()).isEqualTo("DELIVERED");assertThat(received).hasSize(1);
        }finally{release.countDown();job.destroy();}
    }
    @Test void shouldRollbackRequestPublicationAuditAndOutboxAfterActualEnqueueFails() {
        var f=fixture(2);var before=counts();var target=AopTestUtils.<OnCallOpenNotifications>getUltimateTargetObject(notifications);
        doAnswer(i->{i.callRealMethod();throw new IllegalStateException("after actual outbox insert");}).when(target).enqueue(any());assertThatThrownBy(()->publish(f)).isInstanceOf(IllegalStateException.class);assertThat(counts()).isEqualTo(before);
    }
    @Test void shouldRejectOversizedOriginalPublicationWithoutPartialRows() {
        var f=fixture(0);for(int i=0;i<240;i++){long id=user("界".repeat(64));jdbc.sql("INSERT INTO oncall_schedule_member(schedule_id,user_id,active,can_respond,can_manage,version,origin) VALUES (:plan,:id,TRUE,TRUE,FALSE,0,'EXPLICIT')").param("plan",f.plan()).param("id",id).update();}
        var before=counts();assertThatThrownBy(()->publish(f)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_OPEN_PUBLICATION_TOO_LARGE");assertThat(counts()).isEqualTo(before);
    }
    @Test void shouldExposeAuthenticatedTechnicalMetadataWithoutPayloadTokenOrLeaseOwner() throws Exception {
        var row=publish(fixture(1));notifications.dispatchDue();String route="/api/v1/on-call/open-handoffs/"+row.id()+"/notifications";
        mvc.perform(get(route)).andExpect(status().isUnauthorized());var response=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username","auditor","password","OpsPilot@2026")))).andReturn().getResponse();
        String token=json.readTree(response.getContentAsString()).path("data").path("accessToken").asText();var output=mvc.perform(get(route).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true)).andExpect(jsonPath("$.data.deliveries[0].status").value("DELIVERED")).andReturn().getResponse().getContentAsString();
        assertThat(output).doesNotContain("payload","test-open-channel","leaseToken","url","accessToken");
    }
    @Test void shouldRedriveSameFrozenEventWithImmutableReceiptAndNoInlineHttp() {
        var row=publish(fixture(1));code.set(400);notifications.dispatchDue();var failed=delivery(row.id());String frozen=payload(row.id());var cmd=retryCommand(failed);
        var queued=redrive.retry(row.id(),failed.id(),cmd,1,"test");assertThat(queued.replayed()).isFalse();assertThat(queued.currentDelivery().status()).isEqualTo("PENDING");assertThat(queued.currentDelivery().attempts()).isEqualTo(1);
        assertThat(queued.receipt().priorHttpStatus()).isEqualTo(400);assertThat(queued.receipt().priorErrorCode()).isEqualTo("HTTP_400");assertThat(queued.receipt().priorAttempts()).isEqualTo(1);assertThat(received).hasSize(1);
        code.set(200);notifications.dispatchDue();assertThat(delivery(row.id()).status()).isEqualTo("DELIVERED");assertThat(delivery(row.id()).attempts()).isEqualTo(2);var before=counts();
        var replay=redrive.retry(row.id(),failed.id(),cmd,1,"test");assertThat(replay.replayed()).isTrue();assertThat(replay.receipt()).isEqualTo(queued.receipt());assertThat(replay.currentDelivery()).isEqualTo(delivery(row.id()));assertThat(counts()).isEqualTo(before);
        assertThat(received).hasSize(2);assertThat(received.get(0).key()).isEqualTo(received.get(1).key());assertThat(received.get(0).payload()).isEqualTo(received.get(1).payload());assertThat(payload(row.id())).isEqualTo(frozen);assertThat(open.get(row.id()).status()).isEqualTo("OPEN");
    }
    @Test void shouldBoundEachRedriveCycleWithoutErasingLifetimeAttempts() {
        var row=publish(fixture(1));code.set(503);notifications.dispatchDue();due(row.id());notifications.dispatchDue();var first=delivery(row.id());assertThat(first.attempts()).isEqualTo(2);
        var command=retryCommand(first);var original=redrive.retry(row.id(),first.id(),command,1,"test");notifications.dispatchDue();assertThat(delivery(row.id()).status()).isEqualTo("PENDING");assertThat(delivery(row.id()).attempts()).isEqualTo(3);
        due(row.id());notifications.dispatchDue();assertThat(delivery(row.id()).status()).isEqualTo("FAILED");assertThat(delivery(row.id()).attempts()).isEqualTo(4);assertThat(notifications.dispatchDue()).isZero();
        redrive.retry(row.id(),first.id(),retryCommand(delivery(row.id())),1,"test");code.set(200);notifications.dispatchDue();assertThat(delivery(row.id()).attempts()).isEqualTo(5);assertThat(redrive.retry(row.id(),first.id(),command,1,"test").receipt()).isEqualTo(original.receipt());
        assertThat(received).hasSize(5);assertThat(received.stream().map(Received::key).distinct()).hasSize(1);assertThat(received.stream().map(Received::payload).distinct()).hasSize(1);
    }
    @Test void shouldReplayOriginalRedriveAfterWithdrawalWithoutRequeueOrResend() {
        var f=fixture(1);var row=publish(f);code.set(400);notifications.dispatchDue();var failed=delivery(row.id());var cmd=retryCommand(failed);var original=redrive.retry(row.id(),failed.id(),cmd,1,"test");
        open.withdraw(row.id(),operation(),f.owner(),"test");var before=counts();var replay=redrive.retry(row.id(),failed.id(),cmd,1,"test");assertThat(replay.receipt()).isEqualTo(original.receipt());assertThat(counts()).isEqualTo(before);
        assertThatThrownBy(()->redrive.retry(row.id(),failed.id(),retryCommand(failed),1,"test")).isInstanceOf(ApiException.class);notifications.dispatchDue();assertThat(delivery(row.id()).status()).isEqualTo("SKIPPED");assertThat(received).hasSize(1);
    }
    @Test void shouldRejectChangedRedriveIntentAndCrossEventKeyReuse() {
        var row=publish(fixture(1));code.set(400);notifications.dispatchDue();var failed=delivery(row.id());var cmd=retryCommand(failed);redrive.retry(row.id(),failed.id(),cmd,1,"test");
        var other=publish(fixture(1));notifications.dispatchDue();var before=counts();
        for(var altered:List.of(new OnCallOpenNotificationRedrive.Command(cmd.version(),cmd.operationKey(),"different intent"),new OnCallOpenNotificationRedrive.Command(cmd.version()+1,cmd.operationKey(),cmd.reason())))
            assertThatThrownBy(()->redrive.retry(row.id(),failed.id(),altered,1,"test")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_OPEN_NOTIFICATION_OPERATION_KEY_REUSED");
        assertThatThrownBy(()->redrive.retry(other.id(),delivery(other.id()).id(),cmd,1,"test")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_OPEN_NOTIFICATION_OPERATION_KEY_REUSED");assertThat(counts()).isEqualTo(before);
    }
    @Test void shouldRequireCurrentPlanManagerEvenForOriginalRedriveReplay() {
        var f=fixture(1);var row=publish(f);code.set(400);notifications.dispatchDue();var failed=delivery(row.id());var cmd=retryCommand(failed);long manager=user("plan manager");jdbc.sql("UPDATE sys_user SET role_code='OPS_MANAGER' WHERE id=:id").param("id",manager).update();
        assertThatThrownBy(()->redrive.retry(row.id(),failed.id(),cmd,manager,"test")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_PLAN_MANAGEMENT_FORBIDDEN");
        members.change(f.plan(),new OnCallPlanMembershipService.Command(manager,null,true,false,true,UUID.randomUUID().toString(),"manage only"),1,"test");redrive.retry(row.id(),failed.id(),cmd,manager,"test");
        int version=members.list(f.plan()).stream().filter(m->m.userId()==manager).findFirst().orElseThrow().version();members.change(f.plan(),new OnCallPlanMembershipService.Command(manager,version,false,false,false,UUID.randomUUID().toString(),"remove management"),1,"test");
        var before=counts();assertThatThrownBy(()->redrive.retry(row.id(),failed.id(),cmd,manager,"test")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_PLAN_MANAGEMENT_FORBIDDEN");assertThat(counts()).isEqualTo(before);
    }
    @Test void shouldScopeRedriveOperationKeysToTheCurrentlyAuthorizedActor() {
        var f=fixture(1);var row=publish(f);code.set(400);notifications.dispatchDue();var failed=delivery(row.id());var cmd=retryCommand(failed);var admin=redrive.retry(row.id(),failed.id(),cmd,1,"test");notifications.dispatchDue();
        long manager=user("other authorized manager");jdbc.sql("UPDATE sys_user SET role_code='OPS_MANAGER' WHERE id=:id").param("id",manager).update();members.change(f.plan(),new OnCallPlanMembershipService.Command(manager,null,true,false,true,UUID.randomUUID().toString(),"manage only"),1,"test");
        var sameKey=new OnCallOpenNotificationRedrive.Command(delivery(row.id()).version(),cmd.operationKey(),cmd.reason());var other=redrive.retry(row.id(),failed.id(),sameKey,manager,"test");assertThat(other.receipt().id()).isNotEqualTo(admin.receipt().id());assertThat(other.receipt().actorId()).isEqualTo(manager);assertThat(redrive.retry(row.id(),failed.id(),cmd,1,"test").receipt()).isEqualTo(admin.receipt());assertThat(received).hasSize(2);
    }
    @Test void shouldRejectRedriveOfEveryCurrentlyIneligibleOriginalEvent() {
        for(int scenario=0;scenario<6;scenario++) {
            var f=fixture(1);var row=publish(f);code.set(400);notifications.dispatchDue();var failed=delivery(row.id());
            switch(scenario){case 0->revoke(f.plan(),f.targets().get(0));case 1->revoke(f.plan(),f.owner());case 2->jdbc.sql("UPDATE oncall_schedule SET active=FALSE WHERE id=:id").param("id",f.plan()).update();
                case 3->roster.cancel(f.source().id(),0,"actual source cancellation",1L,"test");case 4->open.withdraw(row.id(),operation(),f.owner(),"test");case 5->jdbc.sql("UPDATE sys_user SET status='DISABLED' WHERE id=:id").param("id",f.targets().get(0)).update();}
            var before=counts();assertThatThrownBy(()->redrive.retry(row.id(),failed.id(),retryCommand(failed),1,"test")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_OPEN_NOTIFICATION_INELIGIBLE");assertThat(delivery(row.id())).isEqualTo(failed);assertThat(counts()).isEqualTo(before);
        }
        assertThat(received).hasSize(6);
    }
    @Test void shouldRejectNonfailedStaleInvalidAndWrongHandoffRedriveWithoutWrites() {
        var row=publish(fixture(1));var original=delivery(row.id());var before=counts();
        for(String state:List.of("PENDING","CLAIMED","DELIVERED","SKIPPED")){jdbc.sql("UPDATE oncall_open_handoff_notification SET status=:state WHERE id=:id").param("state",state).param("id",original.id()).update();assertThatThrownBy(()->redrive.retry(row.id(),original.id(),retryCommand(original),1,"test")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_OPEN_NOTIFICATION_VERSION_CONFLICT");}
        jdbc.sql("UPDATE oncall_open_handoff_notification SET status='PENDING' WHERE id=:id").param("id",original.id()).update();code.set(400);notifications.dispatchDue();var failed=delivery(row.id());
        for(var cmd:List.of(new OnCallOpenNotificationRedrive.Command(0,UUID.randomUUID().toString(),"stale"),new OnCallOpenNotificationRedrive.Command(-1,UUID.randomUUID().toString(),"invalid"),new OnCallOpenNotificationRedrive.Command(failed.version(),"not-uuid","invalid"),new OnCallOpenNotificationRedrive.Command(failed.version(),UUID.randomUUID().toString()," ")))
            assertThatThrownBy(()->redrive.retry(row.id(),failed.id(),cmd,1,"test")).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->redrive.retry(row.id()+1000000,failed.id(),retryCommand(failed),1,"test")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_OPEN_NOTIFICATION_NOT_FOUND");assertThat(counts()).isEqualTo(before);
    }
    @Test void shouldRollbackActualRedriveQueueLedgerAndAuditTogether() {
        var row=publish(fixture(1));code.set(400);notifications.dispatchDue();var failed=delivery(row.id());var before=counts();var cmd=retryCommand(failed);
        doAnswer(i->{i.callRealMethod();throw new IllegalStateException("after actual redrive audit insert");}).when(audit).recordAs(eq(1L),eq("test"),eq("ONCALL_OPEN_NOTIFICATION_REDRIVE"),anyString(),eq(failed.id()),anyString());
        assertThatThrownBy(()->redrive.retry(row.id(),failed.id(),cmd,1,"test")).isInstanceOf(IllegalStateException.class);assertThat(delivery(row.id())).isEqualTo(failed);assertThat(counts()).isEqualTo(before);assertThat(received).hasSize(1);
        reset(audit);assertThat(redrive.retry(row.id(),failed.id(),cmd,1,"test").replayed()).isFalse();
    }
    @Test void shouldSerializeConcurrentSameKeyRedrivesToOneReceiptAndOneQueueChange() throws Exception {
        var row=publish(fixture(1));code.set(400);notifications.dispatchDue();var failed=delivery(row.id());var cmd=retryCommand(failed);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{var one=pool.submit(()->{start.await();return redrive.retry(row.id(),failed.id(),cmd,1,"test");});var two=pool.submit(()->{start.await();return redrive.retry(row.id(),failed.id(),cmd,1,"test");});start.countDown();var a=one.get(8,TimeUnit.SECONDS);var b=two.get(8,TimeUnit.SECONDS);assertThat(a.receipt()).isEqualTo(b.receipt());assertThat(a.replayed()).isNotEqualTo(b.replayed());assertThat(delivery(row.id()).version()).isEqualTo(failed.version()+1);assertThat(count("oncall_open_notification_redrive","notification_id="+failed.id())).isEqualTo(1);assertThat(received).hasSize(1);
        }finally{pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void shouldAllowOneConcurrentDifferentKeyRedriveOfTheSameFailedVersion() throws Exception {
        var row=publish(fixture(1));code.set(400);notifications.dispatchDue();var failed=delivery(row.id());var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{Callable<String> attempt=()->{start.await();try{redrive.retry(row.id(),failed.id(),retryCommand(failed),1,"test");return "QUEUED";}catch(ApiException error){return error.code();}};var one=pool.submit(attempt);var two=pool.submit(attempt);start.countDown();assertThat(List.of(one.get(8,TimeUnit.SECONDS),two.get(8,TimeUnit.SECONDS))).containsExactlyInAnyOrder("QUEUED","ONCALL_OPEN_NOTIFICATION_VERSION_CONFLICT");assertThat(count("oncall_open_notification_redrive","notification_id="+failed.id())).isEqualTo(1);assertThat(delivery(row.id()).version()).isEqualTo(failed.version()+1);
        }finally{pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void shouldFenceLateOldLeaseAcrossExhaustionAndManualRedrive() throws Exception {
        var row=publish(fixture(1));jdbc.sql("UPDATE oncall_open_handoff_notification SET attempts=1 WHERE handoff_id=:id").param("id",row.id()).update();blockFirst=true;code.set(503);var pool=Executors.newSingleThreadExecutor();
        try{var old=pool.submit(notifications::dispatchDue);assertThat(arrived.await(3,TimeUnit.SECONDS)).isTrue();jdbc.sql("UPDATE oncall_open_handoff_notification SET lease_until=:now WHERE handoff_id=:id").param("id",row.id()).param("now",now().minusSeconds(1)).update();notifications.dispatchDue();var failed=delivery(row.id());assertThat(failed.status()).isEqualTo("FAILED");redrive.retry(row.id(),failed.id(),retryCommand(failed),1,"test");code.set(200);notifications.dispatchDue();var success=delivery(row.id());assertThat(success.status()).isEqualTo("DELIVERED");assertThat(success.attempts()).isEqualTo(3);release.countDown();old.get(4,TimeUnit.SECONDS);assertThat(delivery(row.id())).isEqualTo(success);assertThat(received).hasSize(2);assertThat(received.get(0).key()).isEqualTo(received.get(1).key());
        }finally{release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void shouldRejectDisabledRedriveButAllowReadOnlyOriginalReceipt() {
        var row=publish(fixture(1));code.set(400);notifications.dispatchDue();var failed=delivery(row.id());
        var disabledProperties=new OnCallOpenNotificationProperties(false,properties.url(),properties.token(),properties.connectTimeout(),properties.readTimeout(),properties.lease(),properties.retryBaseDelay(),properties.retryMaxDelay(),properties.maxAttempts(),properties.batchSize());
        var disabled=new OnCallOpenNotificationRedrive(jdbc,members,recipients,notifications,disabledProperties,audit);var tx=new TransactionTemplate(transactionManager);
        assertThatThrownBy(()->tx.execute(s->disabled.retry(row.id(),failed.id(),retryCommand(failed),1,"test"))).isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_OPEN_NOTIFICATION_DISABLED");
        var cmd=retryCommand(failed);var original=redrive.retry(row.id(),failed.id(),cmd,1,"test");var before=counts();assertThat(tx.execute(s->disabled.retry(row.id(),failed.id(),cmd,1,"test")).receipt()).isEqualTo(original.receipt());assertThat(counts()).isEqualTo(before);
    }
    @Test void shouldRejectRedriveBeforePersistentCountersCanOverflow() {
        var row=publish(fixture(1));code.set(400);notifications.dispatchDue();var failed=delivery(row.id());
        for(boolean versions:List.of(true,false)){jdbc.sql("UPDATE oncall_open_handoff_notification SET version=:version,attempts=:attempts WHERE id=:id").param("id",failed.id()).param("version",versions?Integer.MAX_VALUE-1:failed.version()).param("attempts",versions?1:Integer.MAX_VALUE-1).update();var current=delivery(row.id());assertThatThrownBy(()->redrive.retry(row.id(),failed.id(),retryCommand(current),1,"test")).isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_OPEN_NOTIFICATION_COUNTER_EXHAUSTED");assertThat(delivery(row.id())).isEqualTo(current);}
    }
    @Test void shouldEnforceAuthenticatedRedriveHttpRoleValidationAndOriginalAck() throws Exception {
        var row=publish(fixture(1));code.set(400);notifications.dispatchDue();var failed=delivery(row.id());var command=retryCommand(failed);String route="/api/v1/on-call/open-handoffs/"+row.id()+"/notifications/"+failed.id()+"/redrives",body=json.writeValueAsString(command);
        mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());mvc.perform(post(route).header("Authorization","Bearer "+login("auditor")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        String token=login("admin");mvc.perform(post(route).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{\"operationKey\":\"bad\",\"reason\":\"no version\"}")).andExpect(status().isBadRequest());
        var output=mvc.perform(post(route).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentDelivery.status").value("PENDING")).andExpect(jsonPath("$.data.replayed").value(false)).andReturn().getResponse().getContentAsString();assertThat(output).doesNotContain("payload","test-open-channel","leaseToken","url");
        mvc.perform(post(route).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true));assertThat(received).hasSize(1);
    }
    private String login(String username) throws Exception {var response=mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",username,"password","OpsPilot@2026")))).andExpect(status().isOk()).andReturn().getResponse();return json.readTree(response.getContentAsString()).path("data").path("accessToken").asText();}
    private OnCallOpenNotificationRedrive.Command retryCommand(OnCallOpenNotifications.View row){return new OnCallOpenNotificationRedrive.Command(row.version(),UUID.randomUUID().toString(),"explicit retry of the original event");}
    private record RedriveFact(long notificationId,long actorId,String operationKey,int capturedVersion,int queuedVersion,int priorAttempts,Integer priorHttpStatus,String priorErrorCode,String deliveryKey) {}
    private record Fixture(long plan,long owner,OnCallRosterService.ShiftView source,List<Long> targets) {}
    private Fixture fixture(int targets){long plan=insert(jdbc.sql("INSERT INTO oncall_schedule(service_resource_id,name) VALUES (3,:name)").param("name","notification-"+UUID.randomUUID())),owner=user("publisher");grant(plan,owner);var users=new ArrayList<Long>();for(int i=0;i<targets;i++){long id=user("original recipient");grant(plan,id);users.add(id);}var at=now().truncatedTo(ChronoUnit.SECONDS).plusDays(1);return new Fixture(plan,owner,roster.createManaged(new OnCallRosterService.ShiftCommand(plan,owner,at,at.plusHours(4),false,"notification fixture"),1,"test"),users);}
    private long user(String name){return insert(jdbc.sql("INSERT INTO sys_user(username,password_hash,display_name,role_code) SELECT :username,password_hash,:name,'ON_CALL' FROM sys_user WHERE id=2").param("username","notify-"+UUID.randomUUID()).param("name",name));}
    private long insert(JdbcClient.StatementSpec statement){var key=new GeneratedKeyHolder();statement.update(key,"id");return key.getKey().longValue();}
    private void grant(long plan,long id){members.change(plan,new OnCallPlanMembershipService.Command(id,null,true,true,false,UUID.randomUUID().toString(),"explicit response grant"),1,"test");}
    private void revoke(long plan,long id){int version=members.list(plan).stream().filter(m->m.userId()==id).findFirst().orElseThrow().version();members.change(plan,new OnCallPlanMembershipService.Command(id,version,false,false,false,UUID.randomUUID().toString(),"explicit response removal"),1,"test");}
    private OnCallOpenHandoffService.Command command(Fixture f){return new OnCallOpenHandoffService.Command(f.source().id(),0,UUID.randomUUID().toString(),f.source().startsAt(),f.source().endsAt(),"original notification");}
    private OnCallOpenHandoffService.View publish(Fixture f){var row=open.request(command(f),f.owner(),"test");ownedHandoffs.add(row.id());return row;}
    private OnCallOpenHandoffService.OperationCommand operation(){return new OnCallOpenHandoffService.OperationCommand(0,UUID.randomUUID().toString(),"explicit decision");}
    private OnCallOpenNotifications.View delivery(long id){return notifications.list(id).deliveries().get(0);}
    private void assertSkipped(long id){notifications.dispatchDue();assertThat(notifications.list(id).deliveries()).allSatisfy(v->{assertThat(v.status()).isEqualTo("SKIPPED");assertThat(v.lastErrorCode()).isEqualTo("NO_LONGER_ELIGIBLE");});assertThat(received).isEmpty();}
    private void due(long id){jdbc.sql("UPDATE oncall_open_handoff_notification SET next_attempt_at=:now WHERE handoff_id=:id AND status='PENDING'").param("id",id).param("now",now().minusSeconds(1)).update();}
    private LocalDateTime now(){return jdbc.sql("SELECT CURRENT_TIMESTAMP(6)").query((rs,n)->rs.getObject(1,LocalDateTime.class)).single();}
    private String payload(long id){return jdbc.sql("SELECT payload_json FROM oncall_open_handoff_notification WHERE handoff_id=:id ORDER BY id").param("id",id).query(String.class).list().get(0);}
    private long count(String table,String where){return jdbc.sql("SELECT COUNT(*) FROM "+table+" WHERE "+where).query(Long.class).single();}
    private Map<String,Long> counts(){return Map.of("requests",count("oncall_open_handoff","1=1"),"publications",count("oncall_open_handoff_publication","1=1"),"notifications",count("oncall_open_handoff_notification","1=1"),"redrives",count("oncall_open_notification_redrive","1=1"),"audits",count("audit_log","1=1"));}
}
