package org.trigger.opspilot.investigation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "opspilot.agent.events.outbox-enabled", havingValue = "true")
public class AgentOutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(AgentOutboxRelay.class);
    private final AgentEventOutbox outbox;
    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final String stream;

    public AgentOutboxRelay(AgentEventOutbox outbox, JdbcClient jdbc, StringRedisTemplate redis,
                           @Value("${opspilot.agent.events.redis-stream:opspilot:agent-events}") String stream) {
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.redis = redis;
        this.stream = stream;
    }

    @Scheduled(fixedDelayString = "${opspilot.agent.events.relay-delay:1000}",
            initialDelayString = "${opspilot.agent.events.relay-initial-delay:1000}",
            scheduler = "agentOutboxScheduler")
    public void tick() {
        // Claim only the next message, so later messages do not lose their lease waiting on Redis.
        var claims = outbox.claim(1, LocalDateTime.now(), Duration.ofSeconds(30));
        if (claims.isEmpty()) return;
        var claim = claims.get(0);
        var metadata = jdbc.sql("""
                        SELECT e.run_id, o.attempts FROM agent_event_outbox o
                        JOIN agent_investigation_event e ON e.id = o.event_id
                        WHERE o.event_id = :id
                        """).param("id", claim.eventId())
                .query((rs, row) -> new Metadata(rs.getLong("run_id"), rs.getInt("attempts"))).single();
        try {
            var id = redis.opsForStream().add(StreamRecords.string(Map.of(
                    "schemaVersion", "1", "runId", Long.toString(metadata.runId()),
                    "eventId", Long.toString(claim.eventId()))).withStreamKey(stream));
            if (id == null) throw new IllegalStateException("Redis did not acknowledge XADD");
            if (!outbox.delivered(claim, LocalDateTime.now())) {
                log.warn("Outbox confirmation lost lease for event {}", claim.eventId());
            }
        } catch (RuntimeException exception) {
            outbox.retry(claim, LocalDateTime.now(), retryDelay(metadata.attempts()));
            log.warn("Outbox delivery deferred for event {} ({})", claim.eventId(),
                    exception.getClass().getSimpleName());
        }
    }

    static Duration retryDelay(int attempts) {
        return Duration.ofSeconds(Math.min(60, 1L << Math.min(6, Math.max(0, attempts - 1))));
    }

    private record Metadata(long runId, int attempts) { }
}
