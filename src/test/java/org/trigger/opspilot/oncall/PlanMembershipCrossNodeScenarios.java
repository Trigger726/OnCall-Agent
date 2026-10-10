package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP and independent application processes; no Spring mocks or production test endpoints. */
abstract class PlanMembershipCrossNodeScenarios {
    static final ObjectMapper JSON=new ObjectMapper();
    static final List<String> CASES=List.of("two-live-jvms-concurrent-original-membership-command",
            "remote-revocation-and-immutable-member-ack-without-rebase",
            "committed-http-response-loss-remote-manual-ack-cancel-and-jvm-restart",
            "manager-own-revocation-current-role-and-restoration-fences",
            "two-valid-plans-response-isolation-and-qualified-control",
            "two-jvm-concurrent-original-claim-one-responsibility");
    record OwnedDatabase(String url,String user,String password,String product,String schema,AutoCloseable owner) implements AutoCloseable {
        Connection connect() throws Exception {return DriverManager.getConnection(url,user,password);}
        @Override public void close() throws Exception {owner.close();}
    }
    abstract OwnedDatabase database(Path run) throws Exception;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Process> children=new ArrayList<>();
    private final List<Path> logs=new ArrayList<>();
    private final List<Integer> appPorts=new ArrayList<>(),managementPorts=new ArrayList<>();
    private final List<Map<String,Object>> cases=new ArrayList<>();
    private final Map<String,Object> receipt=new LinkedHashMap<>();
    private OwnedDatabase db;private Path run;private String admin,owner,manager;private LocalDateTime now;

    @Test @Timeout(value=5,unit=TimeUnit.MINUTES)
    void shouldVerifyCurrentMembershipAndOriginalReceiptsAcrossTwoIndependentJvms() throws Exception {
        Path parent=Path.of("target",getClass().getSimpleName().startsWith("MySql")?"oncall-plan-membership-cross-node-mysql-it":"oncall-plan-membership-cross-node-h2-it").toAbsolutePath();
        Files.createDirectories(parent);run=Files.createTempDirectory(parent,"run-");
        Path jar=Path.of("target/opspilot-0.1.0-SNAPSHOT.jar").toAbsolutePath();assertThat(jar).isRegularFile();
        receipt.put("status","RUNNING");receipt.put("jarSha256",java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        String secret=UUID.randomUUID().toString()+UUID.randomUUID();
        OwnedDatabase owned=database(run);
        try {
            db=owned;receipt.put("database",identity());start(jar,secret);start(jar,secret);
            assertThat(children.get(0).isAlive()&&children.get(1).isAlive()).isTrue();assertThat(children.get(0).pid()).isNotEqualTo(children.get(1).pid());
            receipt.put("twoApplicationJvmsObservedAliveTogether",true);
            admin=login(0,"admin");owner=login(0,"zhangwei");manager=login(0,"lina");
            now=LocalDateTime.parse(api(0,"/on-call/roster",admin,null,200,null).path("databaseNow").asText());
            assertThat(count("SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success=TRUE")).isEqualTo(39);
            receipt.put("versionedMigrations",39);
            var member=memberCommand(0,true,true,false);long before=count("SELECT COUNT(*) FROM audit_log WHERE action='ONCALL_MEMBER_CHANGED'");
            var pair=concurrent("/on-call/schedules/1/members",admin,member);
            assertThat(pair.get(0).path("receipt").isObject()).isTrue();assertThat(pair.get(0).path("receipt")).isEqualTo(pair.get(1).path("receipt"));
            assertThat(pair.get(0).path("receipt").path("resultVersion").asInt()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM oncall_schedule_member_operation WHERE operation_key='"+member.get("operationKey")+"'")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='ONCALL_MEMBER_CHANGED'")).isEqualTo(before+1);
            add(0,Map.of("sameOriginalOperation",true,"oneReceipt",true,"oneAudit",true));

            var pending=request(0,1,0);api(1,"/on-call/schedules/1/members",admin,memberCommand(1,false,false,false),200,null);
            var snapshot=fingerprint();
            api(0,claimRoute(pending),manager,operation(),403,"ONCALL_PLAN_RESPONSE_FORBIDDEN");
            api(0,"/on-call/shifts/"+pending.path("sourceShiftId").asLong()+"/cancel",manager,Map.of("version",0,"reason","no plan management"),403,"ONCALL_PLAN_MANAGEMENT_FORBIDDEN");
            var ack=api(0,"/on-call/schedules/1/members",admin,member,200,null);assertThat(ack.path("receipt")).isEqualTo(pair.get(0).path("receipt"));assertThat(ack.path("current").path("version").asInt()).isEqualTo(2);
            var changed=new LinkedHashMap<>(member);changed.put("reason","changed captured reason");api(1,"/on-call/schedules/1/members",admin,changed,409,"ONCALL_MEMBER_KEY_REUSED");
            api(1,"/on-call/schedules/2/members",admin,member,409,"ONCALL_MEMBER_KEY_REUSED");
            api(0,"/on-call/schedules/1/members",admin,memberCommand(0,true,true,true),409,"ONCALL_MEMBER_VERSION_CONFLICT");
            assertThat(fingerprint()).isEqualTo(snapshot);add(1,Map.of("claim403",true,"management403",true,"originalOperationRetained",true,"changedAndCrossPlanKey409",true,"staleVersion409",true,"noWrites",true));

            api(1,"/on-call/schedules/1/members",admin,memberCommand(2,true,true,true),200,null);
            var loss=request(0,1,1);var lostCommand=operation();loseCommittedResponse(0,claimRoute(loss),manager,lostCommand);
            var original=api(1,claimRoute(loss),manager,lostCommand,200,null);assertClaimSql(loss,original);
            api(1,"/on-call/shifts/"+original.path("replacement").path("id").asLong()+"/cancel",admin,Map.of("version",0,"reason","independent cross-node cancellation"),200,null);
            var cancelled=api(1,claimRoute(loss),manager,lostCommand,200,null);assertThat(cancelled.path("replacement").path("cancelledAt").isNull()).isFalse();
            stop(children.get(0));start(jar,secret);assertThat(children.get(1).isAlive()&&children.get(2).isAlive()).isTrue();snapshot=fingerprint();
            var replay=api(2,claimRoute(loss),manager,lostCommand,200,null);assertThat(parts(replay)).isEqualTo(parts(cancelled));
            api(1,claimRoute(loss),manager,operation(),409,"ONCALL_OPEN_HANDOFF_VERSION_CONFLICT");assertThat(fingerprint()).isEqualTo(snapshot);
            add(2,Map.of("statusLine200BeforeSocketClosed",true,"bodyNotRead",true,"originalOperation",cancelled.path("operation"),"requestId",loss.path("id").asLong(),"cancelledCoverageNotRevived",true,"snapshotUnchangedAfterRestartAck",true));

            var self=memberCommand(3,true,true,false);var selfAck=api(2,"/on-call/schedules/1/members",manager,self,200,null);snapshot=fingerprint();
            assertThat(api(1,"/on-call/schedules/1/members",manager,self,200,null).path("receipt")).isEqualTo(selfAck.path("receipt"));
            api(1,"/on-call/schedules/1/members",manager,memberCommand(4,true,true,true),403,"ONCALL_PLAN_MANAGEMENT_FORBIDDEN");assertThat(fingerprint()).isEqualTo(snapshot);
            // Explicit fixture mutation of this owned account exercises current-role checks, never the JWT claim.
            role("AUDITOR");snapshot=fingerprint();for(int node:List.of(1,2))api(node,"/on-call/schedules/1/members",manager,self,403,null);assertThat(fingerprint()).isEqualTo(snapshot);
            role("OPS_MANAGER");snapshot=fingerprint();assertThat(api(1,"/on-call/schedules/1/members",manager,self,200,null).path("receipt")).isEqualTo(selfAck.path("receipt"));
            api(2,"/on-call/schedules/1/members",manager,memberCommand(4,true,true,true),403,"ONCALL_PLAN_MANAGEMENT_FORBIDDEN");assertThat(fingerprint()).isEqualTo(snapshot);
            add(3,Map.of("ownOriginalKey200",true,"newManagement403",true,"bothOldJwtAfterRoleLoss403",true,"roleRestorationDoesNotRestorePlanManagement",true));

            api(1,"/on-call/schedules/2/members",admin,memberCommand(0,false,false,false),200,null);
            var allowed=request(2,1,2);var denied=request(1,2,3);var allowedAck=api(2,claimRoute(allowed),manager,operation(),200,null);assertClaimSql(allowed,allowedAck);
            snapshot=fingerprint();api(1,claimRoute(denied),manager,operation(),403,"ONCALL_PLAN_RESPONSE_FORBIDDEN");assertThat(fingerprint()).isEqualTo(snapshot);
            var control=api(1,claimRoute(denied),admin,operation(),200,null);assertClaimSql(denied,control);assertThat(control.path("request").path("claimedBy").asLong()).isEqualTo(1);
            add(4,Map.of("bothPlansValid",true,"plan1Accepted",true,"plan2SameActor403",true,"plan2QualifiedControl200",true));

            var race=request(2,1,4);var claim=operation();long audits=count("SELECT COUNT(*) FROM audit_log");
            var claims=concurrentLive(claimRoute(race),manager,claim);assertThat(parts(claims.get(0))).isEqualTo(parts(claims.get(1)));assertClaimSql(race,claims.get(0));
            assertThat(count("SELECT COUNT(*) FROM audit_log")).isEqualTo(audits+2);
            add(5,Map.of("requestId",race.path("id").asLong(),"sameOriginalOperation",true,"oneReplacement",true,"oneReceipt",true,"exactlyTwoResponsibilityAudits",true));
            var finalSql=Map.of("requests",count("SELECT COUNT(*) FROM oncall_open_handoff"),"claimed",count("SELECT COUNT(*) FROM oncall_open_handoff WHERE status='CLAIMED'"),
                    "open",count("SELECT COUNT(*) FROM oncall_open_handoff WHERE status='OPEN'"),"claimOperations",count("SELECT COUNT(*) FROM oncall_open_handoff_operation"),
                    "memberOperations",count("SELECT COUNT(*) FROM oncall_schedule_member_operation"),"memberAudits",count("SELECT COUNT(*) FROM audit_log WHERE action='ONCALL_MEMBER_CHANGED'"));
            assertThat(finalSql).containsExactlyInAnyOrderEntriesOf(Map.of("requests",5L,"claimed",4L,"open",1L,"claimOperations",4L,"memberOperations",5L,"memberAudits",5L));
            receipt.put("finalSql",finalSql);assertThat(identity()).isEqualTo(receipt.get("database"));
            receipt.put("status","PASS");
        } catch(Exception|AssertionError failure) {receipt.put("status","FAIL");receipt.put("failure",failure.getClass().getSimpleName());throw failure;}
        finally {
            // Stop both pools before closing the owned TCP server/container; test-owned state only.
            for(var child:children)stop(child);
            owned.close();receipt.put("databaseOwnerStopped",true);
            for(int port:appPorts)free(port);for(int port:managementPorts)free(port);
            receipt.put("cases",cases);receipt.put("startedPids",children.stream().map(Process::pid).toList());receipt.put("allApplicationPidsAbsent",children.stream().noneMatch(Process::isAlive));
            receipt.put("applicationPorts",appPorts);receipt.put("managementPorts",managementPorts);receipt.put("portsFree",true);
            var pools=new ArrayList<Map<String,Object>>();
            for(int i=0;i<logs.size();i++){String text=Files.readString(logs.get(i));boolean complete=text.contains("HikariPool-1 - Start completed.")&&text.contains("HikariPool-1 - Shutdown completed.");
                pools.add(Map.of("pid",children.get(i).pid(),"file",logs.get(i).getFileName().toString(),"completePoolShutdown",complete));if(!complete)receipt.put("status","FAIL");}
            receipt.put("pools",pools);receipt.put("crossMachineOrHaClaimed",false);receipt.put("tokensPersistedToEvidence",false);
            Files.writeString(run.resolve("result.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(receipt));
            System.out.println("PLAN_MEMBERSHIP_CROSS_NODE_RESULT "+Path.of("").toAbsolutePath().relativize(run.resolve("result.json")).toString().replace('\\','/'));
        }
        assertThat(receipt.get("status")).isEqualTo("PASS");assertThat(cases.stream().map(c->c.get("name")).toList()).isEqualTo(CASES);
    }

    private void start(Path jar,String secret) throws Exception {
        int app=port(),management=port();appPorts.add(app);managementPorts.add(management);Path log=run.resolve("jar-"+(children.size()+1)+".log");logs.add(log);
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Duser.timezone=UTC","-jar",jar.toString(),
                "--server.address=127.0.0.1","--server.port="+app,"--management.server.address=127.0.0.1","--management.server.port="+management,
                "--management.endpoints.web.exposure.include=health,prometheus,shutdown","--management.endpoint.shutdown.enabled=true",
                "--spring.datasource.driver-class-name="+(db.product().equals("MySQL")?"com.mysql.cj.jdbc.Driver":"org.h2.Driver"),
                "--spring.h2.console.enabled=false","--opspilot.ai.enabled=false","--opspilot.agent.recovery.enabled=false","--opspilot.oncall.rotation.enabled=false",
                "--opspilot.oncall.escalation.enabled=false","--opspilot.oncall.swap.notification.enabled=false"));
        var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(Map.of("JWT_SECRET",secret,"SPRING_DATASOURCE_URL",db.url(),"SPRING_DATASOURCE_USERNAME",db.user(),"SPRING_DATASOURCE_PASSWORD",db.password()));
        var child=builder.start();children.add(child);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(50);
        while(System.nanoTime()<deadline){assertThat(child.isAlive()).as("owned application JVM remains alive; inspect %s",log).isTrue();
            try{var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+management+"/actuator/health")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()==200&&response.body().contains("\"UP\""))return;}catch(java.io.IOException ignored){}
            Thread.sleep(100);
        }throw new AssertionError("Owned JAR health deadline exceeded");
    }
    private String login(int node,String username) throws Exception {String token=api(node,"/auth/login",null,Map.of("username",username,"password","OpsPilot@2026"),200,null).path("accessToken").asText();assertThat(token).isNotBlank();return token;}
    private JsonNode api(int node,String route,String token,Object body,int status,String code) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+appPorts.get(node)+"/api/v1"+route)).timeout(Duration.ofSeconds(20));
        if(token!=null)request.header("Authorization","Bearer "+token);if(body==null)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).as("HTTP status for node %s route %s",node,route).isEqualTo(status);
        var json=JSON.readTree(response.body());if(code!=null)assertThat(json.path("error").path("code").asText()).isEqualTo(code);return json.path("data");
    }
    private List<JsonNode> concurrent(String route,String token,Object body) throws Exception {return concurrentAt(0,1,route,token,body);}
    private List<JsonNode> concurrentLive(String route,String token,Object body) throws Exception {return concurrentAt(2,1,route,token,body);}
    private List<JsonNode> concurrentAt(int a,int b,String route,String token,Object body) throws Exception {
        var ready=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
        var first=CompletableFuture.supplyAsync(()->gatedApi(a,route,token,body,ready,release));
        var second=CompletableFuture.supplyAsync(()->gatedApi(b,route,token,body,ready,release));
        try {assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();} finally {release.countDown();}
        return List.of(first.get(25,TimeUnit.SECONDS),second.get(25,TimeUnit.SECONDS));
    }
    private JsonNode gatedApi(int node,String route,String token,Object body,java.util.concurrent.CountDownLatch ready,java.util.concurrent.CountDownLatch release){try{ready.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return api(node,route,token,body,200,null);}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}}
    private Map<String,Object> memberCommand(int version,boolean active,boolean respond,boolean manage){return Map.of("userId",3,"expectedVersion",version,"active",active,"canRespond",respond,"canManage",manage,"operationKey",UUID.randomUUID().toString(),"reason","captured cross-node member intent");}
    private Map<String,Object> operation(){return Map.of("version",0,"operationKey",UUID.randomUUID().toString(),"reason","captured cross-node voluntary claim");}
    private JsonNode request(int node,long plan,int offset) throws Exception {
        String start=now.plusDays(10+offset).withNano(0).toString(),end=now.plusDays(10+offset).plusHours(4).withNano(0).toString();
        var shift=api(node,"/on-call/shifts",admin,Map.of("scheduleId",plan,"userId",2,"startsAt",start,"endsAt",end,"override",false,"note","owned future source"),200,null);
        return api(node,"/on-call/open-handoffs",owner,Map.of("sourceShiftId",shift.path("id").asLong(),"sourceVersion",0,"requestKey",UUID.randomUUID().toString(),"startsAt",start,"endsAt",end,"reason","owned voluntary publication"),200,null);
    }
    private String claimRoute(JsonNode request){return "/on-call/open-handoffs/"+request.path("id").asLong()+"/claims";}
    private JsonNode parts(JsonNode value){var parts=JSON.createObjectNode();parts.set("request",value.path("request"));parts.set("replacement",value.path("replacement"));parts.set("operation",value.path("operation"));return parts;}
    private void loseCommittedResponse(int node,String route,String token,Object body) throws Exception {
        byte[] payload=JSON.writeValueAsBytes(body);try(var socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",appPorts.get(node)),5000);socket.setSoTimeout(20000);
            socket.getOutputStream().write(("POST /api/v1"+route+" HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "+token+"\r\nContent-Type: application/json\r\nContent-Length: "+payload.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(payload);socket.getOutputStream().flush();
            var line=new StringBuilder();int next;while((next=socket.getInputStream().read())!=-1&&next!='\n')line.append((char)next);assertThat(line.toString()).startsWith("HTTP/1.1 200");socket.setSoLinger(true,0);
        }
    }
    private void assertClaimSql(JsonNode request,JsonNode ack) throws Exception {long id=request.path("id").asLong();assertThat(ack.path("request").path("status").asText()).isEqualTo("CLAIMED");
        assertThat(count("SELECT COUNT(*) FROM oncall_open_handoff_operation WHERE handoff_id="+id)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM oncall_shift WHERE id="+ack.path("replacement").path("id").asLong()+" AND override_flag=TRUE")).isEqualTo(1);
    }
    private long count(String sql) throws Exception {try(var c=db.connect();var s=c.createStatement();var r=s.executeQuery(sql)){assertThat(r.next()).isTrue();return r.getLong(1);}}
    private void role(String value) throws Exception {try(var c=db.connect();var s=c.prepareStatement("UPDATE sys_user SET role_code=? WHERE id=3")){s.setString(1,value);assertThat(s.executeUpdate()).isEqualTo(1);}}
    private Map<String,Object> identity() throws Exception {try(var c=db.connect()){var m=c.getMetaData();assertThat(m.getDatabaseProductName()).isEqualTo(db.product());
        var identity=new LinkedHashMap<String,Object>();identity.put("product",m.getDatabaseProductName());identity.put("version",m.getDatabaseProductVersion());identity.put("schema",db.schema());
        if(db.product().equals("MySQL")){assertThat(c.getCatalog()).isEqualTo(db.schema());assertThat(m.getDatabaseProductVersion()).startsWith("8.4.");try(var s=c.createStatement();var r=s.executeQuery("SELECT @@server_uuid")){r.next();identity.put("serverUuid",r.getString(1));}}
        return identity;}}
    private String fingerprint() throws Exception {var bytes=new StringBuilder();try(var c=db.connect()){
        for(String table:List.of("oncall_schedule_member","oncall_schedule_member_operation","oncall_shift","oncall_open_handoff","oncall_open_handoff_operation","audit_log")){
            String order=table.equals("oncall_schedule_member")?"schedule_id,user_id":table.equals("oncall_open_handoff_operation")?"handoff_id":"id";
            try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM "+table+" ORDER BY "+order)){int columns=r.getMetaData().getColumnCount();while(r.next()){bytes.append(table);for(int i=1;i<=columns;i++){String v=r.getString(i);bytes.append(v==null?"N":v.length()+":"+v);}}}
        }}return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toString().getBytes(StandardCharsets.UTF_8)));}
    private void add(int index,Map<String,Object> facts){var item=new LinkedHashMap<>(facts);item.put("name",CASES.get(index));cases.add(item);}
    private static int port() throws Exception {try(var socket=new ServerSocket()){socket.bind(new InetSocketAddress("127.0.0.1",0));return socket.getLocalPort();}}
    private static void free(int port) throws Exception {try(var socket=new ServerSocket()){socket.bind(new InetSocketAddress("127.0.0.1",port));}}
    private void stop(Process child) throws Exception {
        if(child.isAlive()){
            int node=children.indexOf(child);
            try {String token=admin!=null?admin:login(node,"admin");
                var shutdown=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+managementPorts.get(node)+"/actuator/shutdown"))
                        .timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.noBody()).build();
                assertThat(http.send(shutdown,HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
                if(child.waitFor(20,TimeUnit.SECONDS))return;
            }catch(Exception|AssertionError ignored){ /* Startup failure still requires exact owned-PID cleanup; missing pool shutdown fails below. */ }
            child.destroy();if(!child.waitFor(10,TimeUnit.SECONDS)){child.destroyForcibly();assertThat(child.waitFor(5,TimeUnit.SECONDS)).isTrue();}
        }
        assertThat(child.isAlive()).isFalse();
    }
}
