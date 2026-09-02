package org.trigger.opspilot.observability.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.trigger.opspilot.observability.LogRedactor;

import java.util.List;
import java.util.Map;

@Component
public class LocalLogsProvider implements LogsProvider {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final LogRedactor redactor;

    public LocalLogsProvider(JdbcClient jdbcClient, ObjectMapper objectMapper, LogRedactor redactor) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.redactor = redactor;
    }

    @Override
    public String id() {
        return "local-logs";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public LogsResult query(LogsQuery query) {
        int[] redactedFields = {0};
        List<LogEntry> entries = jdbcClient.sql("""
                        SELECT event.id, resource.resource_code, event.level, event.logger_name,
                               event.message, event.trace_id, event.metadata_json, event.occurred_at
                        FROM observability_log_event event
                        JOIN cmdb_resource resource ON resource.id = event.resource_id
                        WHERE (event.resource_id = :resourceId
                           OR event.resource_id IN (
                               SELECT target_resource_id FROM cmdb_relation WHERE source_resource_id = :resourceId
                           )
                           OR event.resource_id IN (
                               SELECT source_resource_id FROM cmdb_relation WHERE target_resource_id = :resourceId
                           ))
                          AND event.occurred_at BETWEEN :start AND :end
                        ORDER BY CASE event.level WHEN 'ERROR' THEN 1 WHEN 'WARN' THEN 2 ELSE 3 END,
                                 event.occurred_at DESC, event.id DESC
                        LIMIT :limit
                        """)
                .param("resourceId", query.resourceId()).param("start", query.start()).param("end", query.end())
                .param("limit", Math.max(1, Math.min(100, query.limit())))
                .query((rs, rowNum) -> {
                    String rawMessage = rs.getString("message");
                    Map<String, String> rawMetadata = parseMap(rs.getString("metadata_json"));
                    LogRedactor.RedactionResult safe = redactor.redactLog(rawMessage, rawMetadata);
                    redactedFields[0] += safe.redactedFields();
                    return new LogEntry(String.valueOf(rs.getLong("id")), rs.getString("resource_code"),
                            rs.getString("level"), rs.getString("logger_name"), safe.message(),
                            rs.getString("trace_id"), safe.metadata(),
                            rs.getObject("occurred_at", java.time.LocalDateTime.class));
                }).list();
        String statement = "observability_log_event resources=one-hop incident=" + query.incidentId()
                + " window=[" + query.start() + "," + query.end() + "] limit=" + query.limit();
        return new LogsResult(id(), statement, "db:observability_log_event",
                entries, redactedFields[0], List.of());
    }

    private Map<String, String> parseMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return Map.of("parseError", "invalid metadata");
        }
    }
}
