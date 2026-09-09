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
        "opspilot.agent.events.relay-initial-delay=3600000"
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
        assertThat(AgentOutboxRelay.retryDelay(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(AgentOutboxRelay.retryDelay(100)).isEqualTo(Duration.ofSeconds(60));
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
