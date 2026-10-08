package org.trigger.opspilot.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-native-endpoint;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "management.server.port=0", "spring.ai.dashscope.api-key=cp75-controlled-not-a-real-key", "opspilot.ai.enabled=true",
        "opspilot.assistant.workers=1", "opspilot.assistant.queue-capacity=1", "opspilot.assistant.execution-timeout=8s",
        "opspilot.assistant.authorization-check-delay=100", "opspilot.agent.recovery.enabled=false",
        "opspilot.oncall.rotation.enabled=false", "opspilot.oncall.escalation.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AssistantNativeEndpointIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String FIRST = "  第一段中文🙂\n", LAST = "下一步验证  ";
    static final Map<String, Control> CONTROLS = new ConcurrentHashMap<>();
    static final ExecutorService PROVIDER_THREADS = Executors.newFixedThreadPool(2);
    static final HttpServer PROVIDER = provider();
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired Environment environment;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    // The same real HTTP scenarios run on either database; MySQL must never silently fall back to H2.
    @BeforeEach void requireSelectedDatabase(TestInfo test) throws Exception {
        boolean mysql = environment.getProperty("opspilot.assistant.native.mysql.enabled", Boolean.class, false);
        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            assertThat(metadata.getDatabaseProductName()).isEqualTo(mysql ? "MySQL" : "H2");
            if (mysql) assertThat(metadata.getDatabaseProductVersion()).startsWith("8.4.");
            System.out.println("CP80_NATIVE_DATABASE " + JSON.writeValueAsString(Map.of(
                    "product", metadata.getDatabaseProductName(), "version", metadata.getDatabaseProductVersion(),
                    "case", test.getTestMethod().orElseThrow().getName())));
        }
    }

    // Spring owns startup and shutdown; do not stop MySQL in @AfterAll before its Hikari pool closes.
    @TestConfiguration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "opspilot.assistant.native.mysql.enabled", havingValue = "true")
    static class NativeMySql {
        @Bean
        @ServiceConnection
        MySQLContainer<?> mysql() {
            return new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("opspilot_native_test")
                    .withUsername("opspilot")
                    .withPassword("opspilot-native-test")
                    .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
        }
    }

    @DynamicPropertySource static void modelProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.dashscope.base-url", () -> "http://127.0.0.1:" + PROVIDER.getAddress().getPort());
        registry.add("spring.ai.dashscope.chat.base-url", () -> "http://127.0.0.1:" + PROVIDER.getAddress().getPort());
    }

    @AfterAll static void closeProvider() throws Exception {
        CONTROLS.values().forEach(control -> control.release.countDown());
        PROVIDER.stop(0); PROVIDER_THREADS.shutdownNow();
        assertThat(PROVIDER_THREADS.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
    }

    @Test void shouldDeliverRealNativeTokenBeforeCompletionAndCommitExactAnswer() throws Exception {
        String token = login(), key = UUID.randomUUID().toString(); long id = session(token);
        var control = new Control(); CONTROLS.put(control.question, control);
        var events = new LinkedBlockingQueue<JsonNode>();
        var pending = http.sendAsync(post("/assistant/sessions/" + id + "/stream", token,
                Map.of("content", control.question), key, "text/event-stream"), info -> new ObservedBody(events));
        try {
            assertThat(control.entered.await(4, TimeUnit.SECONDS)).isTrue();
            assertThat(control.nativeProtocol.get()).as("Default endpoint must call the native HTTP stream, not answer().call()").isTrue();
            assertThat(control.incremental.get()).isTrue();
            var generation = events.poll(3, TimeUnit.SECONDS);
            assertThat(generation).isNotNull(); assertThat(generation.path("type").asText()).isEqualTo("generation");
            var delta = events.poll(3, TimeUnit.SECONDS);
            assertThat(delta).isNotNull(); assertThat(delta.path("type").asText()).isEqualTo("token");
            assertThat(delta.path("content").asText()).isEqualTo(FIRST);
            assertThat(delta.path("messageId").isNull()).isTrue(); assertThat(delta.path("evidenceJson").isNull()).isTrue();
            assertThat(control.release.getCount()).isEqualTo(1); assertThat(pending.isDone()).isFalse();
            assertThat(count(id, "USER")).isEqualTo(1); assertThat(count(id, "ASSISTANT")).isZero();
            assertThat(requestState(id, token, key).path("status").asText()).isEqualTo("RUNNING");
            control.release.countDown(); var response = pending.get(4, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(200); assertThat(response.body()).contains("event:done");
            assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
            assertThat(count(id, "ASSISTANT")).isEqualTo(1);
            assertThat(jdbc.sql("SELECT content FROM assistant_message WHERE session_id=:id AND role='ASSISTANT'")
                    .param("id", id).query(String.class).single()).isEqualTo(FIRST + LAST);
            var completed = requestState(id, token, key); assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(completed.path("answerMessageId").asLong()).isPositive(); assertThat(control.calls).hasValue(1);
            var replay = http.send(post("/assistant/sessions/" + id + "/stream", token, Map.of("content", control.question), key), HttpResponse.BodyHandlers.ofString());
            assertThat(replay.statusCode()).isEqualTo(200); assertThat(replay.headers().firstValue("X-OpsPilot-Idempotent-Replay")).contains("true");
            assertThat(replay.body()).contains("event:done").doesNotContain("event:token"); assertThat(control.calls).hasValue(1);
        } finally { control.release.countDown(); pending.get(10, TimeUnit.SECONDS); }
    }

    @Test void shouldCancelAfterPreviewAndReuseWorkerBeforeOldProviderIsReleased() throws Exception {
        String token = login(), key = UUID.randomUUID().toString(); long id = session(token);
        var control = new Control(); CONTROLS.put(control.question, control);
        var events = new LinkedBlockingQueue<JsonNode>();
        var pending = http.sendAsync(post("/assistant/sessions/" + id + "/stream", token, Map.of("content", control.question), key), info -> new ObservedBody(events));
        try {
            awaitPreview(events);
            var cancelled = http.send(post("/assistant/sessions/" + id + "/request/cancel", token, null, key), HttpResponse.BodyHandlers.ofString());
            assertThat(cancelled.statusCode()).isEqualTo(200); assertThat(JSON.readTree(cancelled.body()).path("data").path("status").asText()).isEqualTo("CANCELLED");
            assertThat(pending.get(3, TimeUnit.SECONDS).body()).contains("event:cancelled").doesNotContain("event:done");
            assertThat(count(id, "ASSISTANT")).isZero(); assertThat(control.release.getCount()).isEqualTo(1);
            // Fresh real HTTP work must use the SAME one-worker pool while the abandoned model handler is still gated.
            var fresh = new Control(); fresh.release.countDown(); CONTROLS.put(fresh.question, fresh);
            long nextId = session(token);
            var next = http.send(post("/assistant/sessions/" + nextId + "/stream", token,
                    Map.of("content", fresh.question), UUID.randomUUID().toString()), HttpResponse.BodyHandlers.ofString());
            assertThat(next.statusCode()).isEqualTo(200); assertThat(next.body()).contains("event:token", "event:done");
            assertThat(count(nextId, "ASSISTANT")).isEqualTo(1); assertThat(control.release.getCount()).isEqualTo(1);
            control.release.countDown(); assertThat(requestState(id, token, key).path("status").asText()).isEqualTo("CANCELLED");
            assertThat(count(id, "ASSISTANT")).isZero(); assertThat(control.calls).hasValue(1);
        } finally { control.release.countDown(); pending.get(10, TimeUnit.SECONDS); }
    }

    @Test void shouldTimeoutAnIdleProviderAfterPreviewWithoutCommittingPartialAnswer() throws Exception {
        String token = login(), key = UUID.randomUUID().toString(); long id = session(token);
        var control = new Control(); CONTROLS.put(control.question, control);
        var events = new LinkedBlockingQueue<JsonNode>();
        var pending = http.sendAsync(post("/assistant/sessions/" + id + "/stream", token, Map.of("content", control.question), key), info -> new ObservedBody(events));
        try {
            awaitPreview(events); assertThat(count(id, "ASSISTANT")).isZero();
            assertThat(pending.get(10, TimeUnit.SECONDS).body()).contains("回答超时", "event:error").doesNotContain("event:done");
            assertThat(requestState(id, token, key).path("status").asText()).isEqualTo("TIMED_OUT");
            assertThat(control.release.getCount()).isEqualTo(1); assertThat(count(id, "ASSISTANT")).isZero();
        } finally { control.release.countDown(); pending.get(10, TimeUnit.SECONDS); }
    }

    @Test void shouldRejectNativeQueueSaturationBeforeAdmissionAndReuseCancelledSlot() throws Exception {
        String token = login(), runningKey = UUID.randomUUID().toString(), queuedKey = UUID.randomUUID().toString();
        long runningId = session(token), queuedId = session(token), rejectedId = session(token);
        var running = new Control(); var queued = new Control(); var rejected = new Control();
        CONTROLS.put(running.question, running); CONTROLS.put(queued.question, queued); CONTROLS.put(rejected.question, rejected);
        var events = new LinkedBlockingQueue<JsonNode>();
        var first = http.sendAsync(post("/assistant/sessions/" + runningId + "/stream", token,
                Map.of("content", running.question), runningKey), info -> new ObservedBody(events));
        CompletableFuture<HttpResponse<String>> second = null, retry = null;
        try {
            awaitPreview(events);
            second = http.sendAsync(post("/assistant/sessions/" + queuedId + "/stream", token,
                    Map.of("content", queued.question), queuedKey), HttpResponse.BodyHandlers.ofString());
            awaitQueued(queuedId); assertThat(queued.calls).hasValue(0); assertThat(count(queuedId, "USER")).isZero();
            String rejectedKey = UUID.randomUUID().toString();
            for (String accept : List.of("text/event-stream, application/json", "text/event-stream")) {
                var refusal = http.send(post("/assistant/sessions/" + rejectedId + "/stream", token,
                        Map.of("content", rejected.question), rejectedKey, accept), HttpResponse.BodyHandlers.ofString());
                assertThat(refusal.statusCode()).isEqualTo(503);
                assertThat(refusal.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
                assertThat(JSON.readTree(refusal.body()).path("error").path("code").asText()).isEqualTo("ASSISTANT_QUEUE_SATURATED");
            }
            assertThat(count(rejectedId, "USER")).isZero(); assertThat(count(rejectedId, "ASSISTANT")).isZero(); assertThat(rejected.calls).hasValue(0);
            var lookup = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/assistant/sessions/" + rejectedId + "/request"))
                    .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token).header("Idempotency-Key", rejectedKey).GET().build();
            assertThat(http.send(lookup, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
            assertThat(http.send(post("/assistant/sessions/" + queuedId + "/request/cancel", token, null, queuedKey), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(second.get(3, TimeUnit.SECONDS).body()).contains("event:cancelled").doesNotContain("event:done");
            assertThat(requestState(queuedId, token, queuedKey).path("status").asText()).isEqualTo("CANCELLED");
            assertThat(count(queuedId, "USER")).isZero(); assertThat(count(queuedId, "ASSISTANT")).isZero(); assertThat(queued.calls).hasValue(0);
            rejected.release.countDown();
            retry = http.sendAsync(post("/assistant/sessions/" + rejectedId + "/stream", token,
                    Map.of("content", rejected.question), rejectedKey), HttpResponse.BodyHandlers.ofString());
            awaitQueued(rejectedId); assertThat(rejected.calls).hasValue(0); assertThat(count(rejectedId, "USER")).isZero();
            assertThat(http.send(post("/assistant/sessions/" + runningId + "/request/cancel", token, null, runningKey), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(first.get(3, TimeUnit.SECONDS).body()).contains("event:cancelled").doesNotContain("event:done");
            var recovered = retry.get(4, TimeUnit.SECONDS);
            assertThat(recovered.statusCode()).isEqualTo(200); assertThat(recovered.body()).contains("event:token", "event:done");
            assertThat(running.release.getCount()).isEqualTo(1); assertThat(running.calls).hasValue(1); assertThat(rejected.calls).hasValue(1);
            assertThat(count(runningId, "ASSISTANT")).isZero(); assertThat(count(rejectedId, "USER")).isEqualTo(1); assertThat(count(rejectedId, "ASSISTANT")).isEqualTo(1);
            assertThat(requestState(rejectedId, token, rejectedKey).path("answerMessageId").asLong()).isPositive();
        } finally {
            running.release.countDown(); queued.release.countDown(); rejected.release.countDown(); first.get(10, TimeUnit.SECONDS);
            if (second != null) second.get(10, TimeUnit.SECONDS);
            if (retry != null) retry.get(10, TimeUnit.SECONDS);
        }
    }

    private void awaitQueued(long id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            if (jdbc.sql("SELECT COUNT(*) FROM assistant_request WHERE session_id=:id AND status='QUEUED'")
                    .param("id", id).query(Long.class).single() == 1) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertThat(jdbc.sql("SELECT status FROM assistant_request WHERE session_id=:id").param("id", id).query(String.class).optional()).contains("QUEUED");
    }

    @RepeatedTest(10) void shouldFailOnNativeEofRatherThanAppendRuleFallback() throws Exception {
        String token = login(), key = UUID.randomUUID().toString(); long id = session(token);
        var control = new Control(); control.truncated = true; CONTROLS.put(control.question, control);
        var events = new LinkedBlockingQueue<JsonNode>();
        var pending = http.sendAsync(post("/assistant/sessions/" + id + "/stream", token, Map.of("content", control.question), key), info -> new ObservedBody(events));
        try {
            awaitPreview(events); control.release.countDown();
            var response = pending.get(4, TimeUnit.SECONDS);
            assertThat(response.body()).contains("event:error").doesNotContain("event:done", "event:meta", "当前活跃 Incident");
            assertThat(requestState(id, token, key).path("status").asText()).isEqualTo("FAILED");
            assertThat(count(id, "USER")).isEqualTo(1); assertThat(count(id, "ASSISTANT")).isZero(); assertThat(control.calls).hasValue(1);
        } finally { control.release.countDown(); pending.get(10, TimeUnit.SECONDS); }
    }

    @Test void browserDisconnectShouldContinueOriginalDurableRequestWithoutAnotherPost() throws Exception {
        String token = login(), key = UUID.randomUUID().toString(); long id = session(token);
        var control = new Control(); CONTROLS.put(control.question, control);
        try {
            var response = http.send(post("/assistant/sessions/" + id + "/stream", token, Map.of("content", control.question), key), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line; boolean tokenSeen = false;
                while ((line = reader.readLine()) != null) if (line.equals("event:token")) { tokenSeen = true; break; }
                assertThat(tokenSeen).isTrue();
            }
            assertThat(requestState(id, token, key).path("status").asText()).isEqualTo("RUNNING");
            control.release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            JsonNode state;
            do { state = requestState(id, token, key); if ("COMPLETED".equals(state.path("status").asText())) break; Thread.sleep(20); }
            while (System.nanoTime() < deadline);
            assertThat(state.path("status").asText()).isEqualTo("COMPLETED"); assertThat(state.path("answerMessageId").asLong()).isPositive();
            assertThat(count(id, "ASSISTANT")).isEqualTo(1); assertThat(control.calls).hasValue(1);
        } finally { control.release.countDown(); }
    }

    private static void awaitPreview(BlockingQueue<JsonNode> events) throws Exception {
        var generation = events.poll(4, TimeUnit.SECONDS); assertThat(generation).isNotNull();
        assertThat(generation.path("type").asText()).isEqualTo("generation");
        var token = events.poll(3, TimeUnit.SECONDS); assertThat(token).isNotNull();
        assertThat(token.path("type").asText()).isEqualTo("token"); assertThat(token.path("messageId").isNull()).isTrue();
    }

    @Test void shouldStopRevokedNativePreviewWithoutLateAnswerOrErrorPayload() throws Exception {
        String token = login(), key = UUID.randomUUID().toString(); long id = session(token);
        var control = new Control(); CONTROLS.put(control.question, control); var events = new LinkedBlockingQueue<JsonNode>();
        var pending = http.sendAsync(post("/assistant/sessions/" + id + "/stream", token, Map.of("content", control.question), key), info -> new ObservedBody(events));
        try {
            awaitPreview(events);
            assertThat(http.send(post("/auth/logout-all", token, Map.of(), null), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(pending.get(3, TimeUnit.SECONDS).body()).doesNotContain("event:done", "event:error", LAST);
            assertThat(control.release.getCount()).isEqualTo(1); assertThat(count(id, "ASSISTANT")).isZero();
            assertThat(requestState(id, login(), key).path("status").asText()).isEqualTo("REVOKED");
        } finally { control.release.countDown(); pending.get(10, TimeUnit.SECONDS); }
    }

    @Test void shouldNotResurrectClearedNativePreviewWhenModelCompletesLate() throws Exception {
        String token = login(), key = UUID.randomUUID().toString(); long id = session(token);
        var control = new Control(); CONTROLS.put(control.question, control); var events = new LinkedBlockingQueue<JsonNode>();
        var pending = http.sendAsync(post("/assistant/sessions/" + id + "/stream", token, Map.of("content", control.question), key), info -> new ObservedBody(events));
        try {
            awaitPreview(events);
            var clear = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/assistant/sessions/" + id + "/messages"))
                    .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token).DELETE().build();
            assertThat(http.send(clear, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(pending.get(3, TimeUnit.SECONDS).body()).contains("event:error").doesNotContain("event:done", LAST);
            assertThat(requestState(id, token, key).path("status").asText()).isEqualTo("SUPERSEDED");
            assertThat(count(id, "USER")).isZero(); assertThat(count(id, "ASSISTANT")).isZero();
        } finally { control.release.countDown(); pending.get(10, TimeUnit.SECONDS); }
    }

    @Test void nativeDoneMustNotEscapeAFailedFinalAuditTransaction() throws Exception {
        String token = login(), key = UUID.randomUUID().toString(); long id = session(token);
        var control = new Control(); CONTROLS.put(control.question, control); var events = new LinkedBlockingQueue<JsonNode>();
        var pending = http.sendAsync(post("/assistant/sessions/" + id + "/stream", token, Map.of("content", control.question), key), info -> new ObservedBody(events));
        boolean constraintAdded = false;
        try {
            awaitPreview(events);
            // Scoped isolated database failure AFTER the preview; answer/title/completion must roll back together.
            jdbc.sql("ALTER TABLE audit_log ADD CONSTRAINT cp75_native_audit_failure CHECK (action <> 'ASSISTANT_MESSAGE' OR target_id <> '" + id + "')").update();
            constraintAdded = true; control.release.countDown();
            assertThat(pending.get(4, TimeUnit.SECONDS).body()).contains("event:error").doesNotContain("event:done");
            assertThat(requestState(id, token, key).path("status").asText()).isEqualTo("FAILED");
            assertThat(count(id, "USER")).isEqualTo(1); assertThat(count(id, "ASSISTANT")).isZero();
            assertThat(jdbc.sql("SELECT title FROM assistant_session WHERE id=:id").param("id", id).query(String.class).single()).isEqualTo("新对话");
            assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action='ASSISTANT_MESSAGE' AND target_id=:id")
                    .param("id", Long.toString(id)).query(Long.class).single()).isZero();
            assertThat(http.send(post("/assistant/sessions/" + id + "/stream", token, Map.of("content", control.question), key), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
            assertThat(control.calls).hasValue(1);
        } finally {
            control.release.countDown(); pending.get(10, TimeUnit.SECONDS);
            if (constraintAdded) jdbc.sql("ALTER TABLE audit_log DROP CONSTRAINT cp75_native_audit_failure").update();
        }
    }

    private String login() throws Exception {
        var result = http.send(post("/auth/login", null, Map.of("username", "zhangwei", "password", "OpsPilot@2026"), null), HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(200); return JSON.readTree(result.body()).path("data").path("accessToken").asText();
    }
    private long session(String token) throws Exception {
        var result = http.send(post("/assistant/sessions", token, Map.of(), null), HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(200); return JSON.readTree(result.body()).path("data").path("session").path("id").asLong();
    }
    private HttpRequest post(String route, String token, Object body, String key) throws Exception {
        return post(route, token, body, key, "text/event-stream, application/json");
    }
    private HttpRequest post(String route, String token, Object body, String key, String accept) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + route)).timeout(Duration.ofSeconds(12));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        request.header("Accept", accept);
        if (body != null) request.header("Content-Type", "application/json");
        return request.POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
    }
    private JsonNode requestState(long id, String token, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/assistant/sessions/" + id + "/request"))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token).header("Idempotency-Key", key).GET().build();
        var result = http.send(request, HttpResponse.BodyHandlers.ofString()); assertThat(result.statusCode()).isEqualTo(200);
        return JSON.readTree(result.body()).path("data");
    }
    private long count(long id, String role) {
        return jdbc.sql("SELECT COUNT(*) FROM assistant_message WHERE session_id=:id AND role=:role")
                .param("id", id).param("role", role).query(Long.class).single();
    }
    private static HttpServer provider() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(PROVIDER_THREADS);
            server.createContext("/api/v1/services/aigc/text-generation/generation", exchange -> {
                String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var control = CONTROLS.values().stream().filter(item -> raw.contains(item.question)).findFirst().orElse(null);
                boolean nativeProtocol = "enable".equals(exchange.getRequestHeaders().getFirst("X-DashScope-SSE"));
                if (control != null) {
                    control.calls.incrementAndGet(); control.nativeProtocol.set(nativeProtocol);
                    control.incremental.set(JSON.readTree(raw).path("parameters").path("incremental_output").asBoolean());
                }
                try {
                    if (nativeProtocol) {
                        exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write(frame(FIRST, "null")); exchange.getResponseBody().flush();
                    }
                    if (control != null) { control.entered.countDown(); control.release.await(20, TimeUnit.SECONDS); }
                    if (nativeProtocol) {
                        if (control == null || !control.truncated) { exchange.getResponseBody().write(frame(LAST, "stop")); exchange.getResponseBody().flush(); }
                    }
                    else {
                        byte[] bytes = completion(FIRST + LAST, "stop").getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            server.start(); return server;
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    private static String completion(String content, String reason) throws java.io.IOException {
        return JSON.writeValueAsString(Map.of("output", Map.of("choices", List.of(Map.of("finish_reason", reason,
                "message", Map.of("role", "assistant", "content", content)))), "usage", Map.of("input_tokens", 1, "output_tokens", 1, "total_tokens", 2),
                "request_id", "cp75-controlled"));
    }
    private static byte[] frame(String content, String reason) throws java.io.IOException { return ("data:" + completion(content, reason) + "\n\n").getBytes(StandardCharsets.UTF_8); }
    private static final class Control {
        final String question = "cp75-native-" + UUID.randomUUID();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicBoolean nativeProtocol = new AtomicBoolean(), incremental = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean truncated;
    }
    private static final class ObservedBody implements HttpResponse.BodySubscriber<String> {
        final HttpResponse.BodySubscriber<String> delegate = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final BlockingQueue<JsonNode> events;
        int parsed;
        ObservedBody(BlockingQueue<JsonNode> events) { this.events = events; }
        public CompletionStage<String> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription subscription) { delegate.onSubscribe(subscription); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) { var copy = buffer.duplicate(); byte[] data = new byte[copy.remaining()]; copy.get(data); bytes.writeBytes(data); }
            String text = bytes.toString(StandardCharsets.UTF_8); int end;
            while ((end = text.indexOf("\n\n", parsed)) >= 0) {
                String frame = text.substring(parsed, end); parsed = end + 2;
                for (String line : frame.split("\n")) if (line.startsWith("data:")) {
                    try { events.add(JSON.readTree(line.substring(5))); }
                    catch (java.io.IOException error) { delegate.onError(error); return; }
                }
            }
            delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
