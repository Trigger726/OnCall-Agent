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
            assertThat(count("flyway_schema_history","success=TRUE AND version IS NOT NULL")).isEqualTo(41);
            assertThat(count("flyway_schema_history","success=TRUE AND version='41'")).isEqualTo(1);
            System.out.println("OPEN_NOTIFICATION_DATABASE "+json.writeValueAsString(Map.of("case",info.getTestMethod().orElseThrow().getName(),"product",meta.getDatabaseProductName(),"version",meta.getDatabaseProductVersion(),"schema",connection.getCatalog(),"migration41",true)));
        }
    }
    @AfterEach void cleanup(TestInfo info) throws Exception {
        release.countDown();reset(AopTestUtils.<OnCallOpenNotifications>getUltimateTargetObject(notifications));
        var events=ownedHandoffs.stream().flatMap(id->notifications.list(id).deliveries().stream()).toList();
        var digests=new ArrayList<String>();for(var request:received)digests.add(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(request.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        System.out.println("OPEN_NOTIFICATION_OUTCOME "+json.writeValueAsString(Map.of("case",info.getTestMethod().orElseThrow().getName(),
                "httpCalls",received.size(),"authenticatedRequests",received.stream().filter(Received::authenticated).count(),
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
    private Map<String,Long> counts(){return Map.of("requests",count("oncall_open_handoff","1=1"),"publications",count("oncall_open_handoff_publication","1=1"),"notifications",count("oncall_open_handoff_notification","1=1"),"audits",count("audit_log","1=1"));}
}
