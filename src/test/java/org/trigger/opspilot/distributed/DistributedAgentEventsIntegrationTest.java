package org.trigger.opspilot.distributed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfSystemProperty(named = "opspilot.distributed.it.enabled", matches = "true")
class DistributedAgentEventsIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("opspilot_distributed").withUsername("opspilot").withPassword("test-password");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void shouldBroadcastCommittedEventsAcrossTwoJvmsAndResumeOnTheOtherNode() throws Exception {
        Path evidence = Path.of("target", "distributed-it", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(evidence);
        ExecutorService readers = Executors.newFixedThreadPool(2);
        try (Node a = startNode(evidence, "A", true); Node b = startNode(evidence, "B", false)) {
            assertThat(a.process.pid()).isNotEqualTo(b.process.pid());
            String token = login(a.port);
            var trigger = request(a.port, "/api/v1/incidents/1/investigations/stream?source=DISTRIBUTED_IT&timeoutMs=120000", token)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            var started = HTTP.send(trigger, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(started.statusCode()).isEqualTo(200);
            long runId = Long.parseLong(started.headers().firstValue("X-OpsPilot-Run-Id").orElseThrow());
            // Only the initiating POST is closed; A must keep executing its durable run.
            started.body().close();
            await(() -> Files.exists(evidence.resolve("entered")), Duration.ofSeconds(20), "A enters gated tool");
            try (Stream first = subscribe(a.port, runId, 0, token, readers);
                 Stream second = subscribe(b.port, runId, 0, token, readers)) {
                assertThat(first.gated.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(second.gated.await(10, TimeUnit.SECONDS)).isTrue();
                Files.writeString(evidence.resolve("release"), "continue");
                List<Long> firstIds = first.events.get(30, TimeUnit.SECONDS);
                List<Long> secondIds = second.events.get(30, TimeUnit.SECONDS);
                JsonNode replay = getJson(b.port, "/api/v1/agent-runs/" + runId + "/events?after=0", token).path("data");
                List<Long> databaseIds = new ArrayList<>();
                replay.forEach(event -> databaseIds.add(event.path("id").asLong()));
                assertThat(replay.get(replay.size() - 1).path("eventType").asText()).isEqualTo("RUN_COMPLETED");
                assertThat(firstIds).containsExactlyElementsOf(databaseIds).doesNotHaveDuplicates();
                assertThat(secondIds).containsExactlyElementsOf(databaseIds).doesNotHaveDuplicates();
                assertThat(databaseIds.size()).isGreaterThan(18); // Includes the gated read-only tool.
                long after = databaseIds.get(3);
                try (Stream resumed = subscribe(b.port, runId, after, token, readers)) {
                    assertThat(resumed.events.get(10, TimeUnit.SECONDS))
                            .containsExactlyElementsOf(databaseIds.subList(4, databaseIds.size()));
                }
                Files.writeString(evidence.resolve("result.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                        java.util.Map.of("runId", runId, "nodeAPid", a.process.pid(), "nodeBPid", b.process.pid(),
                                "nodeAPort", a.port, "nodeBPort", b.port, "databaseEventIds", databaseIds,
                                "nodeAEventIds", firstIds, "nodeBEventIds", secondIds,
                                "reconnectAfter", after, "periodicCatchupDisabled", true)));
            }
        } catch (Throwable failure) {
            for (String node : List.of("A", "B")) {
                Path log = evidence.resolve(node + ".log");
                if (Files.exists(log)) {
                    String contents = Files.readString(log);
                    System.err.println(node + " log tail:\n" + contents.substring(Math.max(0, contents.length() - 16000)));
                }
            }
            throw failure;
        } finally {
            readers.shutdownNow();
        }
    }

    private static Node startNode(Path evidence, String name, boolean gated) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var command = new ArrayList<>(List.of(executable, "-Xmx384m", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                DistributedAgentNode.class.getName(), "--server.port=" + port,
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(),
                "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "--spring.h2.console.enabled=false", "--spring.jmx.enabled=false",
                "--opspilot.ai.enabled=false", "--spring.ai.dashscope.api-key=disabled",
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--opspilot.agent.events.outbox-enabled=true",
                "--opspilot.agent.events.relay-delay=100",
                "--opspilot.agent.events.receiver-delay=100",
                "--opspilot.agent.events.catchup-delay=3600000",
                "--opspilot.test.gated-tool=" + gated,
                "--opspilot.test.gate-directory=" + evidence));
        Node node = new Node(port, new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(evidence.resolve(name + ".log").toFile()).start());
        try {
            await(() -> {
                if (!node.process.isAlive()) throw new IllegalStateException("Node " + name + " exited");
                try {
                    return HTTP.send(request(port, "/actuator/health", null).timeout(Duration.ofSeconds(2)).GET().build(),
                            HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
                } catch (Exception exception) { return false; }
            }, Duration.ofSeconds(60), "Node " + name + " ready");
            return node;
        } catch (Throwable failure) {
            node.close();
            throw failure;
        }
    }

    private static String login(int port) throws Exception {
        var response = HTTP.send(request(port, "/api/v1/auth/login", null).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"OpsPilot@2026\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String token = JSON.readTree(response.body()).path("data").path("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private static JsonNode getJson(int port, String path, String token) throws Exception {
        var response = HTTP.send(request(port, path, token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static HttpRequest.Builder request(int port, String path, String token) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    private static Stream subscribe(int port, long runId, long after, String token, ExecutorService readers) throws Exception {
        var response = HTTP.send(request(port, "/api/v1/agent-runs/" + runId + "/events/stream?after=" + after, token)
                .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        return new Stream(response.body(), readers);
    }

    private static void await(BooleanSupplier ready, Duration timeout, String description) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!ready.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out: " + description);
            Thread.sleep(100);
        }
    }

    private record Node(int port, Process process) implements AutoCloseable {
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static final class Stream implements AutoCloseable {
        final InputStream input;
        final CountDownLatch gated = new CountDownLatch(1);
        final Future<List<Long>> events;
        Stream(InputStream input, ExecutorService readers) {
            this.input = input;
            events = readers.submit(() -> {
                List<Long> ids = new ArrayList<>();
                var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    JsonNode event = JSON.readTree(line.substring(5));
                    ids.add(event.path("id").asLong());
                    if ("cross_instance_gate".equals(event.path("toolName").asText())
                            && "STEP_STARTED".equals(event.path("eventType").asText())) gated.countDown();
                    if (Set.of("RUN_COMPLETED", "RUN_FAILED", "RUN_CANCELLED", "RUN_TIMED_OUT", "RUN_REJECTED")
                            .contains(event.path("eventType").asText())) return ids;
                }
                throw new AssertionError("SSE ended before a terminal event");
            });
        }
        public void close() throws Exception { input.close(); events.cancel(true); }
    }
}
