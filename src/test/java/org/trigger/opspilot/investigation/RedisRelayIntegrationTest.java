package org.trigger.opspilot.investigation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfSystemProperty(named = "opspilot.redis.it.enabled", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-redis-relay;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "opspilot.ai.enabled=false", "spring.ai.dashscope.api-key=disabled",
        "opspilot.agent.events.outbox-enabled=true",
        "opspilot.agent.events.relay-initial-delay=3600000",
        "opspilot.agent.events.retention-initial-delay=3600000",
        "opspilot.agent.events.receiver-initial-delay=3600000",
        "opspilot.agent.events.stream-max-length=1"
})
class RedisRelayIntegrationTest {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired AgentOutboxRelay relay;
    @Autowired AgentOutboxRetention retention;
    @Autowired InvestigationService investigations;
    @Autowired AgentRunEventService events;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcClient jdbc;

    @Test
    void shouldRetryRedisFailureAndPreserveEventIdentityAcrossDuplicateDelivery() {
        var prepared = investigations.prepare(1, "REDIS_TEST",
                new InvestigationService.RunActor(1L, "127.0.0.1"), UUID.randomUUID().toString(),
                Duration.ofSeconds(30));
        long eventId = events.list(prepared.runId(), 0).get(0).id();
        String key = "opspilot:agent-events";
        // A real Redis WRONGTYPE response must leave the database event retryable.
        redis.opsForValue().set(key, "not-a-stream");
        var before = java.time.LocalDateTime.now();
        relay.tick();
        assertThat(status(eventId)).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT attempts FROM agent_event_outbox WHERE event_id = :id")
                .param("id", eventId).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT next_attempt_at FROM agent_event_outbox WHERE event_id = :id")
                .param("id", eventId).query(java.time.LocalDateTime.class).single())
                .isAfter(before);
        redis.delete(key);
        makeDue(eventId);
        relay.tick();
        assertThat(status(eventId)).isEqualTo("DELIVERED");
        var records = redis.opsForStream().read(StreamOffset.fromStart(key));
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getValue()).containsEntry("eventId", Long.toString(eventId))
                .containsEntry("runId", Long.toString(prepared.runId())).containsEntry("schemaVersion", "1")
                .hasSize(3);
        // Model XADD success followed by losing the database confirmation: retry keeps eventId.
        makeDue(eventId);
        relay.tick();
        var replay = redis.opsForStream().read(StreamOffset.fromStart(key));
        assertThat(replay).hasSize(2);
        assertThat(replay.get(0).getId()).isNotEqualTo(replay.get(1).getId());
        assertThat(replay.get(0).getValue()).isEqualTo(replay.get(1).getValue());
        jdbc.sql("UPDATE agent_event_outbox SET delivered_at = :at WHERE event_id = :id")
                .param("at", java.time.LocalDateTime.now().minusDays(8)).param("id", eventId).update();
        retention.clean();
        assertThat(redis.opsForStream().size(key)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM agent_event_outbox WHERE event_id = :id")
                .param("id", eventId).query(Long.class).single()).isZero();
        assertThat(events.list(prepared.runId(), 0)).extracting(AgentRunEventService.EventView::id)
                .contains(eventId);
        retention.clean();
        assertThat(redis.opsForStream().size(key)).isEqualTo(1);
        assertThat(AgentOutboxRelay.retryDelay(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(AgentOutboxRelay.retryDelay(100)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void shouldBroadcastWithIndependentCursorsAndRetryListenerFailure() {
        String key = "receiver-test:" + UUID.randomUUID();
        var first = new java.util.ArrayList<Object>();
        var second = new java.util.ArrayList<Object>();
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        var a = new AgentEventReceiver(redis, first::add, key);
        var b = new AgentEventReceiver(redis, event -> {
            if (fail.getAndSet(false)) throw new IllegalStateException("temporary listener failure");
            second.add(event);
        }, key);
        redis.opsForStream().add(key, java.util.Map.of("schemaVersion", "unsupported"));
        var fields = java.util.Map.of("schemaVersion", "1", "runId", "1", "eventId", "2");
        redis.opsForStream().add(key, fields);
        a.poll();
        b.poll();
        assertThat(first).containsExactly(new AgentEventReceiver.Notification(1, 2));
        assertThat(second).isEmpty();
        b.poll();
        assertThat(second).isEqualTo(first);
        a.poll();
        b.poll();
        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        // Redis identities may differ for the same business event; downstream DB replay dedupes.
        redis.opsForStream().add(key, fields);
        a.poll();
        b.poll();
        assertThat(first).hasSize(2);
        assertThat(second).isEqualTo(first);
        // A restarted receiver replays retained hints instead of using a shared group offset.
        var restarted = new java.util.ArrayList<Object>();
        new AgentEventReceiver(redis, restarted::add, key).poll();
        assertThat(restarted).isEqualTo(first);
        redis.delete(key);
    }

    @Test
    void shouldRecoverRegressedReusedAndMissingStreamWithoutRestartingReceiver() throws Exception {
        String key = "receiver-recreation-test:" + UUID.randomUUID();
        var received = new java.util.ArrayList<AgentEventReceiver.Notification>();
        var receiver = new AgentEventReceiver(redis, event -> received.add((AgentEventReceiver.Notification) event), key);
        try {
            addNotification(key, "100-10", 1);
            receiver.poll();
            redis.delete(key);
            addNotification(key, "100-9", 2); // Sequence regresses; no missing-key poll in between.
            receiver.poll();
            receiver.poll();
            assertThat(received).extracting(AgentEventReceiver.Notification::eventId).containsExactly(1L, 2L);
            redis.delete(key);
            addNotification(key, "100-9", 3); // Same transport ID, different business identity.
            receiver.poll();
            receiver.poll();
            assertThat(received).extracting(AgentEventReceiver.Notification::eventId).containsExactly(1L, 2L, 3L);
            redis.delete(key);
            receiver.poll(); // Observe the missing key before recreating it.
            addNotification(key, "1-0", 4);
            receiver.poll();
            receiver.poll(); // Idle on an unchanged tail must not replay it again.
            assertThat(received).extracting(AgentEventReceiver.Notification::eventId).containsExactly(1L, 2L, 3L, 4L);
            receiverEvidence("recreation", java.util.Map.of(
                    "transportIds", java.util.List.of("100-10", "100-9", "100-9", "1-0"),
                    "receivedEventIds", received.stream().map(AgentEventReceiver.Notification::eventId).toList(),
                    "receiverRestarted", false, "databaseFallbackUsed", false));
        } finally { redis.delete(key); }
    }

    @Test
    void shouldBoundRecoveryReadsAndAvoidReplayingAnUnchangedTrimmedTail() throws Exception {
        String key = "receiver-bounded-recovery-test:" + UUID.randomUUID();
        var received = new java.util.ArrayList<AgentEventReceiver.Notification>();
        var receiver = new AgentEventReceiver(redis, event -> received.add((AgentEventReceiver.Notification) event), key);
        var batchSizes = new java.util.ArrayList<Integer>();
        try {
            addNotification(key, "1000-0", 500);
            receiver.poll();
            redis.delete(key);
            for (int index = 1; index <= 205; index++) addNotification(key, index + "-0", index);
            receiver.poll(); // Rewind only; no replay within this detection poll.
            assertThat(received).hasSize(1);
            for (int expected : java.util.List.of(100, 100, 5)) {
                int before = received.size();
                receiver.poll();
                batchSizes.add(received.size() - before);
                assertThat(received.size() - before).isEqualTo(expected);
            }
            assertThat(received.subList(1, received.size())).extracting(AgentEventReceiver.Notification::eventId)
                    .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 205).boxed().toList());
            assertThat(redis.opsForStream().trim(key, 1)).isEqualTo(204L);
            receiver.poll();
            receiver.poll();
            assertThat(received).hasSize(206);
            addNotification(key, "206-0", 206);
            receiver.poll();
            assertThat(received).hasSize(207);
            assertThat(received.get(206).eventId()).isEqualTo(206);
            receiverEvidence("bounded-recovery", java.util.Map.of(
                    "recoveryBatchSizes", batchSizes, "receivedCountAfterTrimIdlePolls", 206,
                    "receivedEventIds", received.stream().map(AgentEventReceiver.Notification::eventId).toList(),
                    "receiverRestarted", false, "databaseFallbackUsed", false));
        } finally { redis.delete(key); }
    }

    private void addNotification(String key, String id, long eventId) {
        redis.opsForStream().add(org.springframework.data.redis.connection.stream.MapRecord.create(key,
                java.util.Map.of("schemaVersion", "1", "runId", "7", "eventId", Long.toString(eventId)))
                .withId(org.springframework.data.redis.connection.stream.RecordId.of(id)));
    }

    private static void receiverEvidence(String scenario, java.util.Map<String, Object> result) throws Exception {
        var directory = java.nio.file.Path.of("target", "redis-it");
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve(scenario + ".json"),
                new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    private void makeDue(long id) {
        jdbc.sql("UPDATE agent_event_outbox SET status = 'PENDING', next_attempt_at = CURRENT_TIMESTAMP WHERE event_id = :id")
                .param("id", id).update();
    }

    private String status(long id) {
        return jdbc.sql("SELECT status FROM agent_event_outbox WHERE event_id = :id")
                .param("id", id).query(String.class).single();
    }
}
