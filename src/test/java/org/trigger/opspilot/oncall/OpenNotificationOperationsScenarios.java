package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.trigger.opspilot.common.ApiException;
import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Separate operational scenarios; original 22 transport cases remain untouched. */
abstract class OpenNotificationOperationsScenarios {
    @Autowired JdbcClient jdbc; @Autowired ObjectMapper json; @Autowired DataSource datasource;
    @Autowired MockMvc mvc; @Autowired OnCallOpenNotificationOperations operations;
    @Autowired OnCallOpenNotificationJob job; @Autowired OnCallOpenNotifications notifications;
    @Autowired OnCallOpenNotificationProperties properties; @Autowired OnCallOpenHandoffService open;
    @Autowired OnCallPlanMembershipService members; @Autowired OnCallRosterService roster;
    private static HttpServer receiver; private static ExecutorService httpWorkers;
    private static volatile CountDownLatch arrived=new CountDownLatch(1),release=new CountDownLatch(1);
    private static final List<Boolean> authenticated=new CopyOnWriteArrayList<>();
    private final List<Map<String,Object>> observations=new ArrayList<>();
    private static final String route="/api/v1/on-call/open-notification-operations";
    @DynamicPropertySource static void configure(DynamicPropertyRegistry p)throws Exception{
        receiver=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);httpWorkers=Executors.newCachedThreadPool();receiver.setExecutor(httpWorkers);
        receiver.createContext("/notify",exchange->{try{exchange.getRequestBody().readAllBytes();authenticated.add("Bearer operations-test-channel".equals(exchange.getRequestHeaders().getFirst("Authorization")));arrived.countDown();release.await(4,TimeUnit.SECONDS);exchange.sendResponseHeaders(200,-1);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});receiver.start();
        p.add("opspilot.oncall.open.notification.enabled",()->true);p.add("opspilot.oncall.open.notification.url",()->"http://127.0.0.1:"+receiver.getAddress().getPort()+"/notify");p.add("opspilot.oncall.open.notification.token",()->"operations-test-channel");
        p.add("opspilot.oncall.open.notification.connect-timeout",()->"200ms");p.add("opspilot.oncall.open.notification.read-timeout",()->"5s");p.add("opspilot.oncall.open.notification.lease",()->"7s");p.add("ONCALL_OPEN_NOTIFICATION_DISPATCH_INITIAL_DELAY",()->3600000);
    }
    @BeforeEach void prepare(TestInfo info)throws Exception{assertIdle();jdbc.sql("DELETE FROM oncall_open_handoff_notification").update();authenticated.clear();arrived=new CountDownLatch(1);release=new CountDownLatch(1);observations.clear();
        try(var c=datasource.getConnection()){var meta=c.getMetaData();String product=meta.getDatabaseProductName();assertThat(product).isEqualTo(getClass().getSimpleName().startsWith("MySql")?"MySQL":"H2");assertThat(jdbc.sql("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version IS NOT NULL").query(Long.class).single()).isEqualTo(42);
            System.out.println("OPEN_NOTIFICATION_OPERATIONS_DATABASE "+json.writeValueAsString(Map.of("case",info.getTestMethod().orElseThrow().getName(),"product",product,"version",meta.getDatabaseProductVersion(),"schema",c.getCatalog(),"migrations",42)));}}
    @AfterEach void finish(TestInfo info)throws Exception{release.countDown();assertIdle();System.out.println("OPEN_NOTIFICATION_OPERATIONS_OUTCOME "+json.writeValueAsString(Map.of("case",info.getTestMethod().orElseThrow().getName(),"httpCalls",authenticated.size(),"authenticatedCalls",authenticated.stream().filter(Boolean::booleanValue).count(),"observations",observations,"finalQueue",operations.get(1).queue(),"noAutomaticClaim",jdbc.sql("SELECT COUNT(*) FROM oncall_open_handoff WHERE claimed_by IS NOT NULL").query(Long.class).single()==0)));}
    @AfterAll static void stop()throws Exception{receiver.stop(0);httpWorkers.shutdownNow();assertThat(httpWorkers.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    @Test void shouldReadEmptyQueueOnlyForActiveAdministrator()throws Exception{
        mvc.perform(get(route)).andExpect(status().isUnauthorized());var value=read("admin");assertThat(value.queue().total()).isZero();assertThat(value.queue().oldestPendingCreatedAt()).isNull();assertThat(value.worker().queuedTicks()).isZero();
        assertThat(value.queue().scope()).isEqualTo("SHARED_DATABASE_AT_STATEMENT");assertThat(value.worker().scope()).isEqualTo("THIS_PROCESS_SINCE_START");
    }
    @Test void shouldRejectUnsupportedMethodAsTyped405WithoutBusinessMutation()throws Exception{
        publish(1);String before=events();mvc.perform(post(route).header("Authorization","Bearer "+token("admin"))).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED")).andExpect(header().string("Allow",org.hamcrest.Matchers.containsString("GET")));assertThat(events()).isEqualTo(before);observations.add(Map.of("actualHttpStatus",405,"typedMethodNotAllowed",true,"originalEventsUnchanged",true));
    }
    @Test void shouldDenyNonAdminAndRevokedAdministratorWithoutMutatingEvents()throws Exception{
        publish(1);String before=events();for(String user:List.of("auditor","zhangwei","lina"))mvc.perform(get(route).header("Authorization","Bearer "+token(user))).andExpect(status().isForbidden());
        String admin=token("admin");jdbc.sql("UPDATE sys_user SET role_code='AUDITOR' WHERE id=1").update();try{mvc.perform(get(route).header("Authorization","Bearer "+admin)).andExpect(status().isForbidden());assertThatThrownBy(()->operations.get(1)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("ONCALL_OPEN_NOTIFICATION_OPERATIONS_FORBIDDEN");}finally{jdbc.sql("UPDATE sys_user SET role_code='ADMIN' WHERE id=1").update();}
        assertThat(events()).isEqualTo(before);observations.add(Map.of("deniedRoles",4,"originalEventsUnchanged",true));
    }
    @Test void shouldAggregateSeededStatusesDueTimesAndExpiredLeasesFromSameDatabaseStatement()throws Exception{
        long handoff=publish(7);var rows=notifications.list(handoff).deliveries();var now=now();set(rows.get(1).id(),"PENDING",now.plusHours(1),null);set(rows.get(2).id(),"CLAIMED",now,now.plusHours(1));set(rows.get(3).id(),"CLAIMED",now,now.minusHours(1));set(rows.get(4).id(),"DELIVERED",now,null);set(rows.get(5).id(),"FAILED",now,null);set(rows.get(6).id(),"SKIPPED",now,null);
        String before=events();var q=read("admin").queue();assertThat(q.total()).isEqualTo(7);assertThat(q.pending()).isEqualTo(2);assertThat(q.pendingDue()).isEqualTo(1);assertThat(q.claimed()).isEqualTo(2);assertThat(q.expiredLeases()).isEqualTo(1);assertThat(q.delivered()).isEqualTo(1);assertThat(q.failed()).isEqualTo(1);assertThat(q.skipped()).isEqualTo(1);assertThat(q.oldestPendingCreatedAt()).isBeforeOrEqualTo(q.databaseNow());assertThat(events()).isEqualTo(before);
        observations.add(Map.of("controlledSqlStates",true,"originalEventsUnchanged",true,"queue",q));
    }
    @Test void shouldExposeBusyRejectsAgainstActualBlockedReceiverWithoutQueuedTicksOrAutomaticClaim()throws Exception{
        long handoff=publish(1);long busy=job.snapshot().busyRejectedTicks();try{job.tick();assertThat(arrived.await(3,TimeUnit.SECONDS)).isTrue();for(int i=0;i<100;i++)job.tick();var v=read("admin");assertThat(v.worker().activeWorkers()).isEqualTo(1);assertThat(v.worker().queuedTicks()).isZero();assertThat(v.worker().busyRejectedTicks()-busy).isEqualTo(100);assertThat(v.queue().claimed()).isEqualTo(1);assertThat(v.queue().delivered()).isZero();assertThat(authenticated).containsExactly(true);
            observations.add(Map.of("busyRejectedDelta",100,"claimedDuringRealHttp",true,"worker",v.worker()));}finally{release.countDown();assertIdle();}
        assertThat(notifications.list(handoff).deliveries().get(0).status()).isEqualTo("DELIVERED");assertThat(open.get(handoff).status()).isEqualTo("OPEN");
    }
    @Test void shouldKeepHistoricalTechnicalCountsReadOnlyAcrossRepeatedOperationsReads()throws Exception{
        long handoff=publish(1);release.countDown();assertThat(notifications.dispatchDue()).isEqualTo(1);String before=events();for(int i=0;i<3;i++)assertThat(read("admin").queue().delivered()).isEqualTo(1);assertThat(events()).isEqualTo(before);assertThat(open.get(handoff).status()).isEqualTo("OPEN");observations.add(Map.of("reads",3,"originalEventsUnchanged",true));
    }
    @Test void shouldSeparateTwoLocalWorkersFromSharedDatabaseQueue()throws Exception{
        publish(1);var other=new OnCallOpenNotificationJob(notifications,properties);try{job.tick();assertThat(arrived.await(3,TimeUnit.SECONDS)).isTrue();job.tick();var local=read("admin");var secondary=new OnCallOpenNotificationOperations(jdbc,properties,other).get(1);assertThat(local.queue()).usingRecursiveComparison().ignoringFields("databaseNow").isEqualTo(secondary.queue());assertThat(secondary.worker().activeWorkers()).isZero();assertThat(secondary.worker().busyRejectedTicks()).isZero();assertThat(local.worker().activeWorkers()).isEqualTo(1);
            observations.add(Map.of("sharedClaimed",secondary.queue().claimed(),"otherWorkerActive",secondary.worker().activeWorkers(),"otherWorkerBusy",secondary.worker().busyRejectedTicks(),"notClusterCounters",true));}finally{release.countDown();assertIdle();other.destroy();}
    }
    private OnCallOpenNotificationOperations.View read(String user)throws Exception{String body=mvc.perform(get(route).header("Authorization","Bearer "+token(user))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(body).doesNotContain("payload","recipientId","deliveryKey","leaseToken","operations-test-channel","accessToken","url");return json.treeToValue(json.readTree(body).path("data"),OnCallOpenNotificationOperations.View.class);}
    private String token(String username)throws Exception{return json.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",username,"password","OpsPilot@2026")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data").path("accessToken").asText();}
    private long publish(int size){long plan=insert(jdbc.sql("INSERT INTO oncall_schedule(service_resource_id,name) VALUES (3,:name)").param("name","operations-"+UUID.randomUUID())),owner=user();grant(plan,owner);for(int i=0;i<size;i++)grant(plan,user());var at=now().withNano(0).plusDays(1);var source=roster.createManaged(new OnCallRosterService.ShiftCommand(plan,owner,at,at.plusHours(4),false,"owned queue fixture"),1,"test");return open.request(new OnCallOpenHandoffService.Command(source.id(),0,UUID.randomUUID().toString(),source.startsAt(),source.endsAt(),"owned queue observation"),owner,"test").id();}
    private long user(){return insert(jdbc.sql("INSERT INTO sys_user(username,password_hash,display_name,role_code) SELECT :name,password_hash,'queue fixture','ON_CALL' FROM sys_user WHERE id=2").param("name","queue-"+UUID.randomUUID()));}
    private void grant(long plan,long user){members.change(plan,new OnCallPlanMembershipService.Command(user,null,true,true,false,UUID.randomUUID().toString(),"owned response grant"),1,"test");}
    private long insert(JdbcClient.StatementSpec s){var key=new GeneratedKeyHolder();s.update(key,"id");return key.getKey().longValue();}
    private void set(long id,String status,LocalDateTime next,LocalDateTime lease){jdbc.sql("UPDATE oncall_open_handoff_notification SET status=:status,next_attempt_at=:next,lease_until=:lease WHERE id=:id").param("id",id).param("status",status).param("next",next).param("lease",lease).update();}
    private LocalDateTime now(){return jdbc.sql("SELECT CURRENT_TIMESTAMP(6)").query((rs,n)->rs.getObject(1,LocalDateTime.class)).single();}
    private String events(){return jdbc.sql("SELECT id,status,version,attempts,delivery_key,payload_json,next_attempt_at,lease_until FROM oncall_open_handoff_notification ORDER BY id").query((rs,n)->rs.getLong(1)+"|"+rs.getString(2)+"|"+rs.getInt(3)+"|"+rs.getInt(4)+"|"+rs.getString(5)+"|"+rs.getString(6)+"|"+rs.getString(7)+"|"+rs.getString(8)).list().toString();}
    private void assertIdle()throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(job.snapshot().activeWorkers()!=0&&System.nanoTime()<until)Thread.sleep(10);assertThat(job.snapshot().activeWorkers()).isZero();}
}
