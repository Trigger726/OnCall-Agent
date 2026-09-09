package org.trigger.opspilot.investigation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "opspilot.agent.events.outbox-enabled", havingValue = "true")
public class AgentOutboxRetention {
    private static final Logger log = LoggerFactory.getLogger(AgentOutboxRetention.class);
    private final AgentEventOutbox outbox;
    private final StringRedisTemplate redis;
    private final String stream;
    private final long maxLength;
    private final int days;

    public AgentOutboxRetention(AgentEventOutbox outbox, StringRedisTemplate redis,
            @Value("${opspilot.agent.events.redis-stream:opspilot:agent-events}") String stream,
            @Value("${opspilot.agent.events.stream-max-length:10000}") long maxLength,
            @Value("${opspilot.agent.events.delivered-retention-days:7}") int days) {
        if (maxLength < 1 || days < 1) throw new IllegalArgumentException("Invalid outbox retention");
        this.outbox = outbox;
        this.redis = redis;
        this.stream = stream;
        this.maxLength = maxLength;
        this.days = days;
    }

    @Scheduled(fixedDelayString = "${opspilot.agent.events.retention-delay:60000}",
            initialDelayString = "${opspilot.agent.events.retention-initial-delay:60000}",
            scheduler = "agentOutboxScheduler")
    public void clean() {
        int removed = outbox.purgeDelivered(LocalDateTime.now().minusDays(days), 1000);
        if (removed > 0) log.info("Removed {} expired delivered outbox records", removed);
        try {
            Long trimmed = redis.opsForStream().trim(stream, maxLength);
            if (trimmed != null && trimmed > 0) log.info("Trimmed {} Redis event notifications", trimmed);
        } catch (RuntimeException exception) {
            log.warn("Redis event retention deferred ({})", exception.getClass().getSimpleName());
        }
    }
}
