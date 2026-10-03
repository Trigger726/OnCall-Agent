package org.trigger.opspilot.investigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.trigger.opspilot.incident.IncidentService;
import org.trigger.opspilot.investigation.tool.InvestigationTool;
import org.trigger.opspilot.security.OpsUserDetailsService;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-agent-revocation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "management.server.port=0",
        "spring.ai.dashscope.api-key=disabled", "opspilot.ai.enabled=false",
        "opspilot.agent.events.catchup-delay=3600000"
})
@Import(AgentSessionRevocationIntegrationTest.ControlledToolConfig.class)
class AgentSessionRevocationIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired OpsUserDetailsService users;
    @Autowired InvestigationService investigations;
    @Autowired AgentRunEventService events;
    @Autowired AgentEventSubscriptions subscriptions;
    @Autowired ControlledTool tool;
    @Autowired org.trigger.opspilot.security.JwtProperties jwtProperties;
    @org.springframework.boot.test.mock.mockito.SpyBean org.trigger.opspilot.security.SessionAuthorization authorization;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    void shouldStopExistingHttpSubscriptionAfterCommittedLogoutWithoutLeakingNewEvent() throws Exception {
        String token = login("zhangwei");
        String peerToken = login("auditor");
        var actor = actor();
        var prepared = investigations.prepare(1, "AUTH_STREAM", actor, UUID.randomUUID().toString(), Duration.ofSeconds(30));
        try (var revoked = stream(prepared.runId(), token); var peer = stream(prepared.runId(), peerToken)) {
            assertThat(revoked.next()).isEqualTo("event:run_queued");
            assertThat(peer.next()).isEqualTo("event:run_queued");
            revoke(token);
            events.record(prepared.runId(), "AFTER_REVOKE", null, null, "RUNNING",
                    Map.of("sentinel", "must-not-reach-revoked-client"), AgentRunEventService.EventSink.NOOP);
            subscriptions.catchUp();
            assertThat(revoked.next()).isEqualTo("EOF");
            assertThat(peer.next()).isEqualTo("event:after_revoke");
            assertThat(get("/api/v1/auth/me", token).statusCode()).isEqualTo(401);
            assertThat(get("/api/v1/auth/me", peerToken).statusCode()).isEqualTo(200);
        } finally {
            investigations.requestCancellation(prepared.runId(), actor, "test cleanup");
        }
    }

    @Test
    void shouldNotStartQueuedRunAfterItsCapturedSessionIsRevoked() throws Exception {
        String token = login("zhangwei");
        var actor = actor();
        var prepared = investigations.prepare(1, "AUTH_QUEUE", actor, UUID.randomUUID().toString(), Duration.ofSeconds(30));
        int calls = tool.calls;
        long reports = count("investigation_report");
        revoke(token);
        var result = investigations.execute(prepared, actor, AgentRunEventService.EventSink.NOOP);
        assertThat(result.status()).isEqualTo("CANCELLED");
        assertThat(result.reportId()).isNull();
        assertThat(tool.calls).isEqualTo(calls);
        assertThat(count("investigation_report")).isEqualTo(reports);
        assertThat(events.list(prepared.runId(), 0)).extracting(AgentRunEventService.EventView::eventType)
                .containsExactly("RUN_QUEUED", "RUN_CANCELLED");
        // A fresh session can still run; cancellation must not disable the account.
        var current = investigations.investigate(1, "AUTH_FRESH", actor(), AgentRunEventService.EventSink.NOOP);
        assertThat(current.reportId()).isNotNull();
    }

    @Test
    void shouldDiscardLateToolResultAfterRealPostStreamSessionIsRevoked() throws Exception {
        String token = login("zhangwei");
        long reports = count("investigation_report");
        long proposals = count("remediation_proposal");
        tool.entered = new CountDownLatch(1);
        tool.release = new CountDownLatch(1);
        var response = client.send(request("/api/v1/incidents/1/investigations/stream", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        long runId = Long.parseLong(response.headers().firstValue("X-OpsPilot-Run-Id").orElseThrow());
        try (var connection = new Connection(response.body())) {
            assertThat(tool.entered.await(3, TimeUnit.SECONDS)).isTrue();
            revoke(token);
            tool.release.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ("RUNNING".equals(runStatus(runId)) && System.nanoTime() < until) Thread.sleep(10);
            assertThat(runStatus(runId)).isEqualTo("CANCELLED");
            assertThat(count("investigation_report")).isEqualTo(reports);
            assertThat(count("remediation_proposal")).isEqualTo(proposals);
            assertThat(events.list(runId, 0)).extracting(AgentRunEventService.EventView::eventType)
                    .contains("RUN_CANCELLED").doesNotContain("EVIDENCE_COLLECTED", "RUN_COMPLETED", "ACTION_PROPOSED");
        } finally {
            tool.release.countDown();
            tool.entered = null;
            tool.release = null;
        }
    }

    @Test
    void shouldCloseIdleSubscriptionAfterPasswordChangeAndKeepOtherAccountValid() throws Exception {
        String token = login("zhangwei"), peerToken = login("auditor");
        var actor = actor();
        String hash = jdbc.sql("SELECT password_hash FROM sys_user WHERE id=:id").param("id", actor.userId()).query(String.class).single();
        var prepared = investigations.prepare(1, "AUTH_PASSWORD", actor, UUID.randomUUID().toString(), Duration.ofSeconds(30));
        try (var connection = stream(prepared.runId(), token)) {
            assertThat(connection.next()).isEqualTo("event:run_queued");
            var response = client.send(request("/api/v1/auth/password", token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                            "currentPassword", "OpsPilot@2026", "newPassword", "CP67-test-only-password@2026"))))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            subscriptions.catchUp();
            assertThat(connection.next()).isEqualTo("EOF");
            assertThat(get("/api/v1/auth/me", token).statusCode()).isEqualTo(401);
            assertThat(get("/api/v1/auth/me", peerToken).statusCode()).isEqualTo(200);
        } finally {
            // Fixture-only restore of seeded password; never roll back auth_version or revive the old token.
            jdbc.sql("UPDATE sys_user SET password_hash=:hash WHERE id=:id").param("hash", hash).param("id", actor.userId()).update();
            investigations.requestCancellation(prepared.runId(), actor, "test cleanup");
        }
    }

    @Test
    void shouldPersistCancellationWhenToolReturnsWithInterruptFlag() throws Exception {
        String token = login("zhangwei");
        tool.entered = new CountDownLatch(1);
        tool.release = new CountDownLatch(1);
        tool.interrupted = new CountDownLatch(1);
        tool.nonCooperative = true;
        var response = client.send(request("/api/v1/incidents/1/investigations/stream", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        long runId = Long.parseLong(response.headers().firstValue("X-OpsPilot-Run-Id").orElseThrow());
        try (var connection = new Connection(response.body())) {
            assertThat(tool.entered.await(3, TimeUnit.SECONDS)).isTrue();
            revoke(token);
            long requestedUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!"CANCEL".equals(jdbc.sql("SELECT termination_kind FROM agent_investigation_run WHERE id=:id")
                    .param("id", runId).query(String.class).single()) && System.nanoTime() < requestedUntil) Thread.sleep(10);
            assertThat(jdbc.sql("SELECT termination_kind FROM agent_investigation_run WHERE id=:id")
                    .param("id", runId).query(String.class).single()).isEqualTo("CANCEL");
            tool.release.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while ("RUNNING".equals(runStatus(runId)) && System.nanoTime() < until) Thread.sleep(10);
            assertThat(runStatus(runId)).isEqualTo("CANCELLED");
            assertThat(events.list(runId, 0)).extracting(AgentRunEventService.EventView::eventType)
                    .contains("RUN_CANCELLED").doesNotContain("EVIDENCE_COLLECTED", "RUN_COMPLETED", "ACTION_PROPOSED");
        } finally {
            tool.release.countDown();
            tool.entered = null; tool.release = null; tool.interrupted = null; tool.nonCooperative = false;
        }
    }

    @Test
    void shouldCloseIdleSubscriptionWhenAccountIsDisabled() throws Exception {
        String token = login("zhangwei");
        var actor = actor();
        var prepared = investigations.prepare(1, "AUTH_DISABLED", actor, UUID.randomUUID().toString(), Duration.ofSeconds(30));
        try (var connection = stream(prepared.runId(), token)) {
            assertThat(connection.next()).isEqualTo("event:run_queued");
            jdbc.sql("UPDATE sys_user SET status='DISABLED' WHERE id=:id").param("id", actor.userId()).update();
            subscriptions.catchUp();
            assertThat(connection.next()).isEqualTo("EOF");
            assertThat(get("/api/v1/auth/me", token).statusCode()).isEqualTo(401);
        } finally {
            jdbc.sql("UPDATE sys_user SET status='ACTIVE' WHERE id=:id").param("id", actor.userId()).update();
            investigations.requestCancellation(prepared.runId(), actor, "test cleanup");
        }
    }

    @Test
    void shouldCancelQueuedInvestigationAfterRoleDowngradeButKeepAuthorizedRead() throws Exception {
        String token = login("zhangwei");
        var actor = actor();
        var prepared = investigations.prepare(1, "AUTH_ROLE", actor, UUID.randomUUID().toString(), Duration.ofSeconds(30));
        try {
            jdbc.sql("UPDATE sys_user SET role_code='AUDITOR' WHERE id=:id").param("id", actor.userId()).update();
            assertThat(investigations.execute(prepared, actor, AgentRunEventService.EventSink.NOOP).status()).isEqualTo("CANCELLED");
            assertThat(get("/api/v1/auth/me", token).statusCode()).isEqualTo(200);
            var denied = client.send(request("/api/v1/incidents/1/investigations", token)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(denied.statusCode()).isEqualTo(403);
        } finally { jdbc.sql("UPDATE sys_user SET role_code='ON_CALL' WHERE id=:id").param("id", actor.userId()).update(); }
    }

    @Test
    void shouldCloseAlreadyEstablishedSubscriptionWhenSignedTokenExpires() throws Exception {
        var principal = users.loadUserByUsername("zhangwei");
        var expires = java.time.Instant.now().plusSeconds(3).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String token = com.auth0.jwt.JWT.create().withIssuer("opspilot").withSubject(principal.username())
                .withClaim("uid", principal.id()).withClaim("sv", principal.authVersion()).withExpiresAt(expires)
                .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256(jwtProperties.jwtSecret()));
        var actor = actor();
        var prepared = investigations.prepare(1, "AUTH_EXPIRY", actor, UUID.randomUUID().toString(), Duration.ofSeconds(30));
        try (var connection = stream(prepared.runId(), token)) {
            assertThat(connection.next()).isEqualTo("event:run_queued");
            while (java.time.Instant.now().isBefore(expires)) Thread.sleep(10);
            subscriptions.catchUp();
            assertThat(connection.next()).isEqualTo("EOF");
            assertThat(get("/api/v1/auth/me", token).statusCode()).isEqualTo(401);
        } finally { investigations.requestCancellation(prepared.runId(), actor, "test cleanup"); }
    }

    @Test
    void shouldRefuseFinalReportCommitWhenSessionIsRevokedAfterLastCheckpoint() throws Exception {
        String token = login("zhangwei");
        long reports = count("investigation_report"), proposals = count("remediation_proposal");
        long timelines = jdbc.sql("SELECT COUNT(*) FROM incident_timeline WHERE event_type='AGENT_INVESTIGATION'").query(Long.class).single();
        var revoked = new java.util.concurrent.atomic.AtomicBoolean();
        org.mockito.Mockito.doAnswer(call -> {
            if (revoked.compareAndSet(false, true)) revoke(token);
            return call.callRealMethod();
        }).when(authorization).authorized(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.eq(true));
        try {
            var response = client.send(request("/api/v1/incidents/1/investigations", token)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(revoked).isTrue();
            assertThat(mapper.readTree(response.body()).path("data").path("status").asText()).isEqualTo("CANCELLED");
            assertThat(count("investigation_report")).isEqualTo(reports);
            assertThat(count("remediation_proposal")).isEqualTo(proposals);
            assertThat(jdbc.sql("SELECT COUNT(*) FROM incident_timeline WHERE event_type='AGENT_INVESTIGATION'").query(Long.class).single()).isEqualTo(timelines);
            assertThat(get("/api/v1/auth/me", token).statusCode()).isEqualTo(401);
        } finally { org.mockito.Mockito.reset(authorization); }
    }

    private InvestigationService.RunActor actor() {
        return new InvestigationService.RunActor(users.loadUserByUsername("zhangwei").id(), "127.0.0.1");
    }

    private String login(String name) throws Exception {
        var response = client.send(request("/api/v1/auth/login", null).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("username", name, "password", "OpsPilot@2026"))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String token = mapper.readTree(response.body()).path("data").path("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private void revoke(String token) throws Exception {
        var response = client.send(request("/api/v1/auth/logout-all", token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private HttpRequest.Builder request(String path, String token) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(8));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return client.send(request(path, token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private Connection stream(long runId, String token) throws Exception {
        var response = client.send(request("/api/v1/agent-runs/" + runId + "/events/stream", token).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        return new Connection(response.body());
    }

    private long count(String table) { return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single(); }
    private String runStatus(long id) { return jdbc.sql("SELECT status FROM agent_investigation_run WHERE id=:id").param("id", id).query(String.class).single(); }

    static class Connection implements AutoCloseable {
        final InputStream input;
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        final ExecutorService reader = Executors.newSingleThreadExecutor();
        Connection(InputStream input) {
            this.input = input;
            reader.submit(() -> {
                try (var buffered = new BufferedReader(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = buffered.readLine()) != null) if (line.startsWith("event:")) lines.add(line);
                    lines.add("EOF");
                } catch (Exception exception) { lines.add("READ_FAILED"); }
            });
        }
        String next() throws InterruptedException { return lines.poll(3, TimeUnit.SECONDS); }
        public void close() throws Exception { input.close(); reader.shutdownNow(); assertThat(reader.awaitTermination(3, TimeUnit.SECONDS)).isTrue(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledToolConfig { @Bean ControlledTool revocationTool() { return new ControlledTool(); } }

    static class ControlledTool implements InvestigationTool {
        volatile CountDownLatch entered, release, interrupted;
        volatile boolean nonCooperative;
        volatile int calls;
        public int order() { return -1; }
        public String name() { return "controlled_authorization_boundary"; }
        public String title() { return "受控授权边界"; }
        public ToolResult execute(IncidentService.IncidentDetail incident) {
            calls++;
            if (entered != null) {
                entered.countDown();
                boolean wasInterrupted = false;
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                try {
                    while (true) {
                        try {
                            if (!release.await(Math.max(1, until - System.nanoTime()), TimeUnit.NANOSECONDS))
                                throw new IllegalStateException("test release timeout");
                            break;
                        } catch (InterruptedException exception) {
                            wasInterrupted = true;
                            if (interrupted != null) interrupted.countDown();
                            if (!nonCooperative) throw new IllegalStateException(exception);
                        }
                    }
                } finally { if (wasInterrupted || nonCooperative) Thread.currentThread().interrupt(); }
            }
            return new ToolResult("late controlled evidence", List.of(new ToolEvidence("TEST", "controlled:auth", java.time.LocalDateTime.now(), "must be discarded")));
        }
    }
}
