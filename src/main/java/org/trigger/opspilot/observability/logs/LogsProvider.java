package org.trigger.opspilot.observability.logs;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface LogsProvider {
    String id();

    int priority();

    boolean available();

    LogsResult query(LogsQuery query);

    record LogsQuery(long incidentId, long resourceId, String resourceCode, String resourceName,
                     LocalDateTime start, LocalDateTime end, int limit) {
    }

    record LogEntry(String ref, String resourceCode, String level, String loggerName,
                    String message, String traceId, Map<String, String> metadata,
                    LocalDateTime occurredAt) {
    }

    record LogsResult(String providerId, String query, String externalRef,
                      List<LogEntry> entries, int redactedFields, List<String> warnings) {
        public LogsResult withWarnings(List<String> value) {
            return new LogsResult(providerId, query, externalRef, entries, redactedFields, value);
        }
    }
}
