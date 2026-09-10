package org.trigger.opspilot.investigation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Instance-local broadcast cursor. Notifications are hints, never replay facts. */
@Component
@ConditionalOnProperty(name = "opspilot.agent.events.outbox-enabled", havingValue = "true")
public class AgentEventReceiver {
    private static final Logger log = LoggerFactory.getLogger(AgentEventReceiver.class);
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher publisher;
    private final String stream;
    private String cursor = "0-0";

    public AgentEventReceiver(StringRedisTemplate redis, ApplicationEventPublisher publisher,
                              @Value("${opspilot.agent.events.redis-stream:opspilot:agent-events}") String stream) {
        this.redis = redis;
        this.publisher = publisher;
        this.stream = stream;
    }

    @Scheduled(fixedDelayString = "${opspilot.agent.events.receiver-delay:1000}",
            initialDelayString = "${opspilot.agent.events.receiver-initial-delay:1000}",
            scheduler = "agentReceiverScheduler")
    public synchronized void poll() {
        try {
            var records = redis.opsForStream().read(StreamReadOptions.empty().count(100),
                    StreamOffset.create(stream, ReadOffset.from(cursor)));
            if (records == null) return;
            for (var record : records) {
                Notification notification = parse(record.getValue());
                if (notification == null) {
                    log.warn("Ignoring invalid Agent notification {}", record.getId());
                } else {
                    // Synchronous listeners must only enqueue/coalesce work, never send SSE here.
                    // A listener failure leaves this record retryable (duplicates are expected).
                    publisher.publishEvent(notification);
                }
                cursor = record.getId().getValue();
            }
        } catch (RuntimeException exception) {
            log.warn("Agent notification receive failed ({})", exception.getClass().getSimpleName());
        }
    }

    private static Notification parse(Map<Object, Object> fields) {
        if (!"1".equals(fields.get("schemaVersion"))) return null;
        try {
            long runId = Long.parseLong(String.valueOf(fields.get("runId")));
            long eventId = Long.parseLong(String.valueOf(fields.get("eventId")));
            return runId > 0 && eventId > 0 ? new Notification(runId, eventId) : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    public record Notification(long runId, long eventId) { }
}
