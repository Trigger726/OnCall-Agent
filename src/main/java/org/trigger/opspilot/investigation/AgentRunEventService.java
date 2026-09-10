package org.trigger.opspilot.investigation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.trigger.opspilot.common.ApiException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class AgentRunEventService {
    private static final Logger log = LoggerFactory.getLogger(AgentRunEventService.class);
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final AgentRunQueryService runQueryService;
    private final TransactionTemplate transactionTemplate;
    private final boolean outboxEnabled;

    public AgentRunEventService(JdbcClient jdbcClient, ObjectMapper objectMapper,
                                AgentRunQueryService runQueryService,
                                TransactionTemplate transactionTemplate,
                                @Value("${opspilot.agent.events.outbox-enabled:false}") boolean outboxEnabled) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.runQueryService = runQueryService;
        this.transactionTemplate = transactionTemplate;
        this.outboxEnabled = outboxEnabled;
    }

    public EventView record(long runId, String eventType,
                            String phase, String toolName, String status,
                            Map<String, Object> payload, EventSink sink) {
        LocalDateTime createdAt = LocalDateTime.now();
        String payloadJson = serialize(payload);
        EventView event = transactionTemplate.execute(transaction -> {
            int sequence = jdbcClient.sql("""
                            SELECT next_event_sequence
                            FROM agent_investigation_run
                            WHERE id = :runId
                            FOR UPDATE
                            """)
                    .param("runId", runId).query(Integer.class).single();
            jdbcClient.sql("""
                            UPDATE agent_investigation_run
                            SET next_event_sequence = :nextSequence
                            WHERE id = :runId
                            """)
                    .param("nextSequence", sequence + 1).param("runId", runId).update();
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcClient.sql("""
                            INSERT INTO agent_investigation_event(
                              run_id, sequence_no, event_type, phase, tool_name, status, payload_json, created_at)
                            VALUES (:runId, :sequence, :eventType, :phase, :toolName, :status, :payload, :createdAt)
                            """)
                    .param("runId", runId).param("sequence", sequence).param("eventType", eventType)
                    .param("phase", phase).param("toolName", toolName).param("status", status)
                    .param("payload", payloadJson).param("createdAt", createdAt)
                    .update(keyHolder, "id");
            Number key = keyHolder.getKey();
            EventView saved = key == null ? null : new EventView(key.longValue(), runId, sequence, eventType,
                    phase, toolName, status, payloadJson, createdAt);
            if (saved != null) {
                if (outboxEnabled) {
                    jdbcClient.sql("INSERT INTO agent_event_outbox(event_id) VALUES (:eventId)")
                            .param("eventId", saved.id()).update();
                }
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            sink.publish(saved);
                        } catch (RuntimeException exception) {
                            // Delivery is best effort; the committed event remains replayable.
                            log.warn("Live delivery failed for Agent run {} event {} ({})",
                                    saved.runId(), saved.id(), exception.getClass().getSimpleName());
                        }
                    }
                });
            }
            return saved;
        });
        if (event == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "AGENT_EVENT_CREATE_FAILED", "无法保存 Agent 运行事件");
        }
        return event;
    }

    public List<EventView> list(long runId, long afterEventId) {
        runQueryService.get(runId);
        return page(runId, afterEventId, Integer.MAX_VALUE);
    }

    public EventView validateCursor(long runId, long afterEventId) {
        runQueryService.get(runId);
        if (afterEventId == 0) return null;
        if (afterEventId < 0) throw new ApiException(HttpStatus.BAD_REQUEST,
                "AGENT_CURSOR_INVALID", "事件游标必须为非负整数");
        var cursor = page(runId, afterEventId - 1, 1);
        if (cursor.isEmpty() || cursor.get(0).id() != afterEventId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_CURSOR_INVALID", "游标不属于当前调查运行");
        }
        return cursor.get(0);
    }

    List<EventView> page(long runId, long afterEventId, int limit) {
        return jdbcClient.sql("""
                        SELECT id, run_id, sequence_no, event_type, phase, tool_name,
                               status, payload_json, created_at
                        FROM agent_investigation_event
                        WHERE run_id = :runId AND id > :afterEventId
                        ORDER BY id
                        LIMIT :limit
                        """)
                .param("runId", runId).param("afterEventId", Math.max(0, afterEventId))
                .param("limit", limit)
                .query((rs, rowNum) -> new EventView(
                        rs.getLong("id"), rs.getLong("run_id"), rs.getInt("sequence_no"),
                        rs.getString("event_type"), rs.getString("phase"), rs.getString("tool_name"),
                        rs.getString("status"), rs.getString("payload_json"),
                        rs.getObject("created_at", LocalDateTime.class))).list();
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "AGENT_EVENT_SERIALIZATION_FAILED", "Agent 事件无法序列化");
        }
    }

    @FunctionalInterface
    public interface EventSink {
        EventSink NOOP = event -> {
        };

        void publish(EventView event);
    }

    public record EventView(long id, long runId, int sequence, String eventType,
                            String phase, String toolName, String status,
                            String payloadJson, LocalDateTime createdAt) {
    }
}
