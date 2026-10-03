package org.trigger.opspilot.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-assistant-revocation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver", "management.server.port=0",
        "spring.ai.dashscope.api-key=disabled", "opspilot.ai.enabled=false",
        "opspilot.assistant.workers=1", "opspilot.assistant.queue-capacity=1",
        "opspilot.assistant.execution-timeout=4s", "opspilot.assistant.authorization-check-delay=100"
})
class AssistantSessionRevocationIntegrationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @MockBean AssistantAiService ai;
    @org.springframework.boot.test.mock.mockito.SpyBean AssistantService assistant;
    @Autowired AssistantExecutionManager execution;
    @Autowired org.trigger.opspilot.security.JwtProperties jwtProperties;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static final String LATE_ANSWER = "CP68-controlled-late-answer-must-not-be-persisted-or-sent";

    @Test void shouldRejectSynchronousRequestWhenStreamCapacityIsFullWithoutWrites() throws Exception {
        verifySharedCapacity(true);
    }

    @Test void shouldIncludeSynchronousWorkInStreamCapacityAndAllowReplacement() throws Exception {
        verifySharedCapacity(false);
    }

    private void verifySharedCapacity(boolean runningStream) throws Exception {
        String token = login();
        long runningId = createSession(token), queuedId = createSession(token), rejectedId = createSession(token);
        String question = "mixed-capacity-" + UUID.randomUUID();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        gate(question, entered, release);
        var running = http.sendAsync(message(runningId, token, question, runningStream), HttpResponse.BodyHandlers.ofString());
        java.util.concurrent.CompletableFuture<HttpResponse<String>> queuedRequest = null;
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            queuedRequest = http.sendAsync(message(queuedId, token, "queued mixed question", true), HttpResponse.BodyHandlers.ofString());
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (execution.queued() != 1 && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(execution.queued()).isEqualTo(1);
            assertThat(count(queuedId, "USER")).isZero();
            var rejected = http.send(message(rejectedId, token, "rejected mixed question", !runningStream), HttpResponse.BodyHandlers.ofString());
            assertThat(rejected.statusCode()).isEqualTo(503);
            assertThat(rejected.body()).contains("ASSISTANT_QUEUE_SATURATED");
            assertThat(count(rejectedId, "USER")).isZero();
            assertThat(count(rejectedId, "ASSISTANT")).isZero();
            assertThat(audits(rejectedId)).isZero();
            release.countDown();
            assertThat(running.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(queuedRequest.get(5, TimeUnit.SECONDS).body()).contains("event:done");
            var fresh = http.send(message(rejectedId, token, "replacement mixed question", false), HttpResponse.BodyHandlers.ofString());
            assertThat(fresh.statusCode()).isEqualTo(200);
            assertThat(count(rejectedId, "USER")).isEqualTo(1);
            assertThat(count(rejectedId, "ASSISTANT")).isEqualTo(1);
            assertThat(audits(rejectedId)).isEqualTo(1);
        } finally {
            release.countDown(); running.get(8, TimeUnit.SECONDS);
            if (queuedRequest != null) queuedRequest.get(8, TimeUnit.SECONDS);
        }
    }

    @Test void shouldTimeoutRunningAndQueuedSynchronousRequestsBeforeProviderRelease() throws Exception {
        String token = login();
        long runningId = createSession(token), queuedId = createSession(token);
        String question = "sync-timeout-" + UUID.randomUUID();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        gate(question, entered, release);
        var running = http.sendAsync(message(runningId, token, question, false), HttpResponse.BodyHandlers.ofString());
        java.util.concurrent.CompletableFuture<HttpResponse<String>> queuedRequest = null;
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            queuedRequest = http.sendAsync(message(queuedId, token, "queued timeout question", false), HttpResponse.BodyHandlers.ofString());
            var response = running.get(6, TimeUnit.SECONDS);
            var queuedResponse = queuedRequest.get(2, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(504);
            assertThat(queuedResponse.statusCode()).isEqualTo(504);
            assertThat(response.body()).contains("ASSISTANT_EXECUTION_TIMEOUT").doesNotContain(LATE_ANSWER);
            assertThat(queuedResponse.body()).contains("ASSISTANT_EXECUTION_TIMEOUT").doesNotContain(LATE_ANSWER);
            assertThat(release.getCount()).isEqualTo(1);
            assertThat(count(queuedId, "USER")).isZero();
            assertThat(count(queuedId, "ASSISTANT")).isZero();
            assertThat(audits(queuedId)).isZero();
            assertThat(execution.queued()).isZero();
            release.countDown(); awaitIdle();
            assertThat(count(runningId, "USER")).isEqualTo(1);
            assertThat(count(runningId, "ASSISTANT")).isZero();
            assertThat(audits(runningId)).isZero();
            var fresh = http.send(message(queuedId, token, "fresh after budget", false), HttpResponse.BodyHandlers.ofString());
            assertThat(fresh.statusCode()).isEqualTo(200);
            assertThat(count(queuedId, "USER")).isEqualTo(1);
            assertThat(count(queuedId, "ASSISTANT")).isEqualTo(1);
        } finally {
            release.countDown(); running.get(8, TimeUnit.SECONDS);
            if (queuedRequest != null) queuedRequest.get(8, TimeUnit.SECONDS);
        }
    }

    @Test void shouldNotSendCommittedAnswerAfterLogoutCompletesBeforeControllerReturns() throws Exception {
        verifySynchronousReturnFence(true);
    }
    @Test void shouldNotSendCommittedAnswerAfterLeaseExpiresBeforeControllerReturns() throws Exception {
        verifySynchronousReturnFence(false);
    }
    private void verifySynchronousReturnFence(boolean revoke) throws Exception {
        String normal = login();
        long sessionId = createSession(normal);
        var identity = com.auth0.jwt.JWT.decode(normal);
        long owner = identity.getClaim("uid").asLong();
        var expiry = java.time.Instant.now().plusSeconds(2);
        String token = revoke ? normal : com.auth0.jwt.JWT.create().withIssuer("opspilot").withSubject(identity.getSubject())
                .withClaim("uid", owner).withClaim("sv", identity.getClaim("sv").asLong()).withExpiresAt(expiry)
                .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256(jwtProperties.jwtSecret()));
        String question = "return-guard-" + UUID.randomUUID();
        var committed = new CountDownLatch(1);
        var returnToController = new CountDownLatch(1);
        when(ai.answer(anyString(), anyString(), anyString())).thenReturn(LATE_ANSWER);
        org.mockito.Mockito.doAnswer(invocation -> {
            var answer = invocation.callRealMethod();
            committed.countDown();
            assertThat(returnToController.await(5, TimeUnit.SECONDS)).isTrue();
            return answer;
        }).when(assistant).sendMessage(org.mockito.ArgumentMatchers.eq(sessionId), org.mockito.ArgumentMatchers.eq(owner),
                org.mockito.ArgumentMatchers.eq(question), org.mockito.ArgumentMatchers.any(org.trigger.opspilot.security.SessionAuthorization.Lease.class),
                org.mockito.ArgumentMatchers.any(Runnable.class));
        var pending = http.sendAsync(message(sessionId, token, question, false), HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(committed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(count(sessionId, "ASSISTANT")).isEqualTo(1);
            assertThat(audits(sessionId)).isEqualTo(1);
            if (revoke) assertThat(post("/api/v1/auth/logout-all", normal, Map.of()).statusCode()).isEqualTo(200);
            else while (java.time.Instant.now().isBefore(expiry)) Thread.sleep(10);
            returnToController.countDown();
            var response = pending.get(3, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).doesNotContain(LATE_ANSWER);
            // It completed under valid authority before revocation: retain history for a new valid login.
            assertThat(count(sessionId, "ASSISTANT")).isEqualTo(1);
            assertThat(audits(sessionId)).isEqualTo(1);
            String fresh = login();
            var history = http.send(request("/api/v1/assistant/sessions/" + sessionId, fresh).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(history.statusCode()).isEqualTo(200);
            assertThat(history.body()).contains(LATE_ANSWER);
        } finally { returnToController.countDown(); pending.get(8, TimeUnit.SECONDS); }
    }

    @Test
    void shouldRejectLateSynchronousAnswerAfterCommittedLogoutAndAllowFreshLogin() throws Exception {
        verifyRevocation(false);
    }

    @Test
    void shouldDiscardLateStreamAnswerAfterCommittedLogoutAndAllowFreshLogin() throws Exception {
        verifyRevocation(true);
    }

    private void verifyRevocation(boolean stream) throws Exception {
        String token = login();
        long sessionId = createSession(token);
        String question = "controlled-" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        gate(question, entered, release);
        var pending = http.sendAsync(message(sessionId, token, question, stream), HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(count(sessionId, "USER")).isEqualTo(1);
            assertThat(post("/api/v1/auth/logout-all", token, Map.of()).statusCode()).isEqualTo(200);
            if (stream) {
                assertThat(pending.get(3, TimeUnit.SECONDS).body()).doesNotContain(LATE_ANSWER);
                assertThat(release.getCount()).isEqualTo(1); // Transport stops before the non-cooperative model returns.
            }
            release.countDown();
            var result = pending.get(8, TimeUnit.SECONDS);
            String answer = stream ? result.body().lines().filter(line -> line.startsWith("data:"))
                    .map(line -> {
                        try { return json.readTree(line.substring(5)).path("content").asText(); }
                        catch (Exception exception) { throw new IllegalStateException(exception); }
                    }).reduce("", String::concat) : result.body();
            assertThat(answer).doesNotContain(LATE_ANSWER);
            if (!stream) assertThat(result.statusCode()).isEqualTo(401);
            assertThat(count(sessionId, "ASSISTANT")).isZero();
            assertThat(audits(sessionId)).isZero();
            String fresh = login();
            var successful = http.send(message(sessionId, fresh, "fresh evidence question", false), HttpResponse.BodyHandlers.ofString());
            assertThat(successful.statusCode()).isEqualTo(200);
            assertThat(count(sessionId, "ASSISTANT")).isEqualTo(1);
            assertThat(audits(sessionId)).isEqualTo(1);
        } finally { release.countDown(); pending.get(8, TimeUnit.SECONDS); }
    }

    @Test
    void shouldNotResurrectClearedMessagesWhenLateProviderReturns() throws Exception {
        String token = login();
        long sessionId = createSession(token);
        String question = "clear-" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        gate(question, entered, release);
        var pending = http.sendAsync(message(sessionId, token, question, false), HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var cleared = http.send(request("/api/v1/assistant/sessions/" + sessionId + "/messages", token)
                    .DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(cleared.statusCode()).isEqualTo(200);
            release.countDown();
            var result = pending.get(8, TimeUnit.SECONDS);
            assertThat(count(sessionId, "USER")).isZero();
            assertThat(count(sessionId, "ASSISTANT")).isZero();
            assertThat(audits(sessionId)).isZero();
            assertThat(result.statusCode()).isEqualTo(409);
        } finally { release.countDown(); pending.get(8, TimeUnit.SECONDS); }
    }

    @Test
    void shouldNotResurrectDeletedSessionWhenLateProviderReturns() throws Exception {
        String token = login();
        long sessionId = createSession(token);
        String question = "delete-" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        gate(question, entered, release);
        var pending = http.sendAsync(message(sessionId, token, question, false), HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var deleted = http.send(request("/api/v1/assistant/sessions/" + sessionId, token).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(deleted.statusCode()).isEqualTo(200);
            release.countDown();
            var result = pending.get(5, TimeUnit.SECONDS);
            assertThat(result.statusCode()).isEqualTo(404);
            assertThat(count(sessionId, "USER")).isZero();
            assertThat(count(sessionId, "ASSISTANT")).isZero();
            assertThat(audits(sessionId)).isZero();
        } finally { release.countDown(); pending.get(8, TimeUnit.SECONDS); }
    }

    @Test
    void shouldReturnSafeTimeoutBeforeProviderReleaseAndDiscardItsLateAnswer() throws Exception {
        String token = login();
        long sessionId = createSession(token);
        String question = "timeout-" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        gate(question, entered, release);
        var pending = http.sendAsync(message(sessionId, token, question, true), HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var result = pending.get(6, TimeUnit.SECONDS);
            assertThat(result.statusCode()).isEqualTo(200); // SSE transport; timeout is an explicit error event, not a done event.
            assertThat(result.body()).contains("event:error", "回答超时").doesNotContain("event:done", LATE_ANSWER);
            assertThat(release.getCount()).isEqualTo(1);
            release.countDown();
            awaitIdle();
            assertThat(count(sessionId, "USER")).isEqualTo(1);
            assertThat(count(sessionId, "ASSISTANT")).isZero();
            assertThat(audits(sessionId)).isZero();
        } finally { release.countDown(); pending.get(8, TimeUnit.SECONDS); }
    }

    @RepeatedTest(8)
    void shouldRejectSaturatedHttpRequestWithoutWritesAndReleaseRevokedQueuedSlot() throws Exception {
        String token = login();
        long runningId = createSession(token), queuedId = createSession(token), rejectedId = createSession(token);
        String question = "saturation-" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        gate(question, entered, release);
        var running = http.sendAsync(message(runningId, token, question, true), HttpResponse.BodyHandlers.ofString());
        java.util.concurrent.CompletableFuture<HttpResponse<String>> queuedRequest = null;
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            queuedRequest = http.sendAsync(message(queuedId, token, "queued question", true), HttpResponse.BodyHandlers.ofString());
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (execution.queued() != 1 && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(execution.queued()).isEqualTo(1);
            var rejected = http.send(message(rejectedId, token, "must never write", true), HttpResponse.BodyHandlers.ofString());
            assertThat(rejected.statusCode()).isEqualTo(503);
            assertThat(rejected.body()).contains("ASSISTANT_QUEUE_SATURATED");
            assertThat(count(rejectedId, "USER")).isZero();
            assertThat(count(rejectedId, "ASSISTANT")).isZero();
            assertThat(audits(rejectedId)).isZero();
            assertThat(post("/api/v1/auth/logout-all", token, Map.of()).statusCode()).isEqualTo(200);
            try { running.get(3, TimeUnit.SECONDS); }
            catch (java.util.concurrent.TimeoutException error) {
                System.out.println("Assistant diagnostic: managed=" + execution.managed() + ", queued=" + execution.queued());
                Thread.getAllStackTraces().forEach((thread, stack) -> {
                    if (thread.getName().startsWith("opspilot-")) {
                        System.out.println(thread.getName() + " " + thread.getState());
                        for (var frame : stack) System.out.println("  " + frame);
                    }
                });
                throw error;
            }
            queuedRequest.get(3, TimeUnit.SECONDS);
            assertThat(release.getCount()).isEqualTo(1);
            assertThat(count(queuedId, "USER")).isZero();
            assertThat(count(queuedId, "ASSISTANT")).isZero();
            assertThat(execution.queued()).isZero();
            String fresh = login();
            var replacement = http.sendAsync(message(rejectedId, fresh, "replacement question", true), HttpResponse.BodyHandlers.ofString());
            release.countDown();
            assertThat(replacement.get(5, TimeUnit.SECONDS).body()).contains("event:done");
            assertThat(count(rejectedId, "USER")).isEqualTo(1);
            assertThat(count(rejectedId, "ASSISTANT")).isEqualTo(1);
            assertThat(audits(rejectedId)).isEqualTo(1);
        } finally {
            release.countDown(); running.get(8, TimeUnit.SECONDS);
            if (queuedRequest != null) queuedRequest.get(8, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldCloseExpiredStreamBeforeProviderReleaseWithoutPersistingItsAnswer() throws Exception {
        String normal = login();
        long sessionId = createSession(normal);
        var claims = com.auth0.jwt.JWT.decode(normal);
        String shortLived = com.auth0.jwt.JWT.create().withIssuer("opspilot").withSubject(claims.getSubject())
                .withClaim("uid", claims.getClaim("uid").asLong()).withClaim("sv", claims.getClaim("sv").asLong())
                .withExpiresAt(java.time.Instant.now().plusSeconds(2))
                .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256(jwtProperties.jwtSecret()));
        String question = "expiry-" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        gate(question, entered, release);
        var pending = http.sendAsync(message(sessionId, shortLived, question, true), HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(pending.get(4, TimeUnit.SECONDS).body()).doesNotContain("event:done", LATE_ANSWER);
            assertThat(release.getCount()).isEqualTo(1);
            release.countDown(); awaitIdle();
            assertThat(count(sessionId, "ASSISTANT")).isZero();
            assertThat(audits(sessionId)).isZero();
            assertThat(http.send(request("/api/v1/auth/me", shortLived).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        } finally { release.countDown(); pending.get(8, TimeUnit.SECONDS); }
    }

    void awaitIdle() throws Exception {
        // Queue a real non-AI task behind the still-running call; a stopped Future alone is not proof of worker exit.
        var settled = new CountDownLatch(1);
        var lease = new org.trigger.opspilot.security.SessionAuthorization.Lease(
                com.auth0.jwt.JWT.decode(login()).getClaim("uid").asLong(), "zhangwei",
                jdbc.sql("SELECT auth_version FROM sys_user WHERE username='zhangwei'").query(Long.class).single(), java.time.Instant.MAX);
        execution.submit(lease, work -> settled.countDown(), reason -> {});
        assertThat(settled.await(3, TimeUnit.SECONDS)).isTrue();
    }

    void gate(String question, CountDownLatch entered, CountDownLatch release) {
        when(ai.answer(anyString(), anyString(), anyString())).thenAnswer(call -> {
            if (!question.equals(call.getArgument(2))) return null;
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("controlled Provider timeout");
            return LATE_ANSWER;
        });
    }

    String login() throws Exception {
        var result = post("/api/v1/auth/login", null, Map.of("username", "zhangwei", "password", "OpsPilot@2026"));
        assertThat(result.statusCode()).isEqualTo(200);
        String token = json.readTree(result.body()).path("data").path("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    long createSession(String token) throws Exception {
        var result = post("/api/v1/assistant/sessions", token, Map.of());
        assertThat(result.statusCode()).isEqualTo(200);
        return json.readTree(result.body()).path("data").path("session").path("id").asLong();
    }

    HttpRequest message(long id, String token, String content, boolean stream) throws Exception {
        return request("/api/v1/assistant/sessions/" + id + (stream ? "/stream" : "/messages"), token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("content", content)))).build();
    }

    HttpResponse<String> post(String path, String token, Object data) throws Exception {
        return http.send(request(path, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data))).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpRequest.Builder request(String path, String token) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request;
    }

    long count(long sessionId, String role) {
        return jdbc.sql("SELECT COUNT(*) FROM assistant_message WHERE session_id=:id AND role=:role")
                .param("id", sessionId).param("role", role).query(Long.class).single();
    }

    long audits(long sessionId) {
        return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action='ASSISTANT_MESSAGE' AND target_id=:id")
                .param("id", String.valueOf(sessionId)).query(Long.class).single();
    }
}
