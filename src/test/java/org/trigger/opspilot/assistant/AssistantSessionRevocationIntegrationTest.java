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

    @Test void shouldReplayCompletedKeyAcrossSyncAndStreamWithoutWrites() throws Exception {
        String token = login();
        long id = createSession(token);
        String key = UUID.randomUUID().toString();
        when(ai.answer(anyString(), anyString(), anyString())).thenReturn(LATE_ANSWER);
        var first = http.send(keyedMessage(id, token, "one durable question", false, key), HttpResponse.BodyHandlers.ofString());
        assertThat(first.statusCode()).isEqualTo(200);
        long answerId = json.readTree(first.body()).path("data").path("id").asLong();
        var replay = http.send(keyedMessage(id, token, "one durable question", false, key), HttpResponse.BodyHandlers.ofString());
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(replay.body()).path("data").path("id").asLong()).isEqualTo(answerId);
        assertThat(replay.headers().firstValue("X-OpsPilot-Idempotent-Replay")).contains("true");
        var stream = http.send(keyedMessage(id, token, "one durable question", true, key), HttpResponse.BodyHandlers.ofString());
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.body()).contains("event:done", "\"messageId\":" + answerId);
        assertThat(count(id, "USER")).isEqualTo(1);
        assertThat(count(id, "ASSISTANT")).isEqualTo(1);
        assertThat(audits(id)).isEqualTo(1);
        org.mockito.Mockito.verify(ai, org.mockito.Mockito.times(1)).answer(anyString(), anyString(), anyString());
    }

    @Test void shouldRejectReusedKeyWithDifferentQuestionWithoutWrites() throws Exception {
        String token = login();
        long id = createSession(token);
        String key = UUID.randomUUID().toString();
        assertThat(http.send(keyedMessage(id, token, "original question", false, key), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        var conflict = http.send(keyedMessage(id, token, "different question", false, key), HttpResponse.BodyHandlers.ofString());
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("ASSISTANT_IDEMPOTENCY_CONFLICT");
        assertThat(count(id, "USER")).isEqualTo(1);
        assertThat(count(id, "ASSISTANT")).isEqualTo(1);
        assertThat(audits(id)).isEqualTo(1);
    }

    @Test void shouldRejectDuplicateInFlightKeyWithoutCallingProvider() throws Exception {
        String token = login();
        long id = createSession(token);
        String key = UUID.randomUUID().toString(), question = "in-flight-key-" + UUID.randomUUID();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        gate(question, entered, release);
        var running = http.sendAsync(keyedMessage(id, token, question, false, key), HttpResponse.BodyHandlers.ofString());
        java.util.concurrent.CompletableFuture<HttpResponse<String>> duplicate = null;
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            duplicate = http.sendAsync(keyedMessage(id, token, question, false, key), HttpResponse.BodyHandlers.ofString());
            var rejected = duplicate.get(2, TimeUnit.SECONDS);
            assertThat(rejected.statusCode()).isEqualTo(409);
            assertThat(rejected.body()).contains("ASSISTANT_REQUEST_IN_PROGRESS");
            assertThat(count(id, "USER")).isEqualTo(1);
            release.countDown();
            assertThat(running.get(3, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(count(id, "ASSISTANT")).isEqualTo(1);
            assertThat(audits(id)).isEqualTo(1);
            org.mockito.Mockito.verify(ai, org.mockito.Mockito.times(1)).answer(anyString(), anyString(), anyString());
        } finally { release.countDown(); running.get(8, TimeUnit.SECONDS); if (duplicate != null) duplicate.get(8, TimeUnit.SECONDS); }
    }

    @Test void shouldKeepClearedKeyTombstoneWithoutResurrectingMessages() throws Exception {
        String token = login(), key = UUID.randomUUID().toString();
        long id = createSession(token);
        assertThat(http.send(keyedMessage(id, token, "clear keyed question", false, key), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(http.send(request("/api/v1/assistant/sessions/" + id + "/messages", token).DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        var state = requestState(id, token, key);
        assertThat(state.statusCode()).isEqualTo(200);
        var data = json.readTree(state.body()).path("data");
        assertThat(data.path("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(data.path("questionMessageId").isNull()).isTrue();
        assertThat(data.path("answerMessageId").isNull()).isTrue();
        var repeat = http.send(keyedMessage(id, token, "clear keyed question", false, key), HttpResponse.BodyHandlers.ofString());
        assertThat(repeat.statusCode()).isEqualTo(409);
        assertThat(repeat.body()).contains("ASSISTANT_REQUEST_SUPERSEDED");
        assertThat(count(id, "USER")).isZero(); assertThat(count(id, "ASSISTANT")).isZero();
        assertThat(requestCount(id)).isEqualTo(1);
    }

    @Test void shouldExposeKeyedTimeoutBeforeProviderReleaseAndNeverRetryItAutomatically() throws Exception {
        verifyStoppedKey(false);
    }

    @Test void shouldExposeRevokedKeyToFreshOwnerWithoutRevivingItsOriginalWork() throws Exception {
        verifyStoppedKey(true);
    }

    private void verifyStoppedKey(boolean revoke) throws Exception {
        String token = login(), key = UUID.randomUUID().toString(), question = "stopped-key-" + UUID.randomUUID();
        long id = createSession(token);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        gate(question, entered, release);
        var pending = http.sendAsync(keyedMessage(id, token, question, false, key), HttpResponse.BodyHandlers.ofString());
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(json.readTree(requestState(id, token, key).body()).path("data").path("status").asText()).isEqualTo("RUNNING");
            if (revoke) assertThat(post("/api/v1/auth/logout-all", token, Map.of()).statusCode()).isEqualTo(200);
            assertThat(pending.get(6, TimeUnit.SECONDS).statusCode()).isEqualTo(revoke ? 401 : 504);
            assertThat(release.getCount()).isEqualTo(1);
            String current = revoke ? login() : token;
            assertThat(json.readTree(requestState(id, current, key).body()).path("data").path("status").asText()).isEqualTo(revoke ? "REVOKED" : "TIMED_OUT");
            assertThat(http.send(keyedMessage(id, current, question, false, key), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
            assertThat(count(id, "USER")).isEqualTo(1); assertThat(count(id, "ASSISTANT")).isZero(); assertThat(audits(id)).isZero();
            release.countDown(); awaitIdle();
            assertThat(count(id, "ASSISTANT")).isZero();
            org.mockito.Mockito.verify(ai, org.mockito.Mockito.times(1)).answer(anyString(), anyString(), anyString());
            assertThat(http.send(keyedMessage(id, current, "explicit new question", false, UUID.randomUUID().toString()), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        } finally { release.countDown(); pending.get(8, TimeUnit.SECONDS); }
    }

    @Test void shouldRejectInvalidKeysAndOtherOwnersStatusWithoutWrites() throws Exception {
        String token = login(), key = UUID.randomUUID().toString();
        long id = createSession(token);
        var invalid = http.send(keyedMessage(id, token, "must not write", false, "bad/key"), HttpResponse.BodyHandlers.ofString());
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("ASSISTANT_IDEMPOTENCY_KEY_INVALID");
        assertThat(count(id, "USER")).isZero(); assertThat(requestCount(id)).isZero();
        assertThat(http.send(keyedMessage(id, token, "owner question", false, key), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        String other = json.readTree(post("/api/v1/auth/login", null, Map.of("username", "auditor", "password", "OpsPilot@2026")).body()).path("data").path("accessToken").asText();
        assertThat(requestState(id, other, key).statusCode()).isEqualTo(404);
        assertThat(http.send(keyedMessage(id, other, "owner question", false, key), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        assertThat(count(id, "USER")).isEqualTo(1); assertThat(requestCount(id)).isEqualTo(1);
    }

    @Test void shouldReplayCompletedKeyWithoutTakingSaturatedCapacity() throws Exception {
        String token = login(), key = UUID.randomUUID().toString();
        long replayId = createSession(token), runningId = createSession(token), queuedId = createSession(token);
        var original = http.send(keyedMessage(replayId, token, "original complete question", false, key), HttpResponse.BodyHandlers.ofString());
        long answerId = json.readTree(original.body()).path("data").path("id").asLong();
        String question = "replay-busy-" + UUID.randomUUID();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        gate(question, entered, release);
        var running = http.sendAsync(message(runningId, token, question, false), HttpResponse.BodyHandlers.ofString());
        java.util.concurrent.CompletableFuture<HttpResponse<String>> queued = null;
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            queued = http.sendAsync(message(queuedId, token, "queued busy question", true), HttpResponse.BodyHandlers.ofString());
            long end = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (execution.queued() != 1 && System.nanoTime() < end) Thread.sleep(10);
            assertThat(execution.queued()).isEqualTo(1);
            var replay = http.sendAsync(keyedMessage(replayId, token, "original complete question", false, key), HttpResponse.BodyHandlers.ofString()).get(2, TimeUnit.SECONDS);
            assertThat(replay.statusCode()).isEqualTo(200);
            assertThat(json.readTree(replay.body()).path("data").path("id").asLong()).isEqualTo(answerId);
            assertThat(count(replayId, "USER")).isEqualTo(1); assertThat(audits(replayId)).isEqualTo(1);
            assertThat(release.getCount()).isEqualTo(1);
        } finally { release.countDown(); running.get(8, TimeUnit.SECONDS); if (queued != null) queued.get(8, TimeUnit.SECONDS); }
    }

    HttpResponse<String> requestState(long id, String token, String key) throws Exception {
        return http.send(request("/api/v1/assistant/sessions/" + id + "/request", token).header("Idempotency-Key", key).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    long requestCount(long id) { return jdbc.sql("SELECT COUNT(*) FROM assistant_request WHERE session_id=:id").param("id", id).query(Long.class).single(); }

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
            var rejected = http.send(keyedMessage(rejectedId, token, "rejected mixed question", !runningStream, UUID.randomUUID().toString()), HttpResponse.BodyHandlers.ofString());
            assertThat(rejected.statusCode()).isEqualTo(503);
            assertThat(rejected.body()).contains("ASSISTANT_QUEUE_SATURATED");
            assertThat(count(rejectedId, "USER")).isZero();
            assertThat(count(rejectedId, "ASSISTANT")).isZero();
            assertThat(audits(rejectedId)).isZero();
            assertThat(requestCount(rejectedId)).isZero();
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
                org.mockito.ArgumentMatchers.any(Runnable.class), org.mockito.ArgumentMatchers.nullable(AssistantRequestStore.Identity.class));
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
        return keyedMessage(id, token, content, stream, null);
    }

    HttpRequest keyedMessage(long id, String token, String content, boolean stream, String key) throws Exception {
        var builder = request("/api/v1/assistant/sessions/" + id + (stream ? "/stream" : "/messages"), token);
        if (key != null) builder.header("Idempotency-Key", key);
        return builder
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
