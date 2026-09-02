package org.trigger.opspilot.observability.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.trigger.opspilot.observability.LogRedactor;
import org.trigger.opspilot.observability.ObservabilityProperties;
import org.trigger.opspilot.observability.ProviderGuard;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class LokiLogsProvider implements LogsProvider {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final ObservabilityProperties.Loki properties;
    private final ProviderGuard providerGuard;
    private final ObjectMapper objectMapper;
    private final LogRedactor redactor;
    private final RestClient restClient;

    public LokiLogsProvider(ObservabilityProperties properties,
                            ProviderGuard providerGuard,
                            ObjectMapper objectMapper,
                            LogRedactor redactor) {
        this.properties = properties.getLoki();
        this.providerGuard = providerGuard;
        this.objectMapper = objectMapper;
        this.redactor = redactor;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) this.properties.getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) this.properties.getReadTimeout().toMillis());
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(normalizeBaseUrl(this.properties.getBaseUrl()))
                .requestFactory(requestFactory);
        if (!blank(this.properties.getTenantId())) {
            builder.defaultHeader("X-Scope-OrgID", this.properties.getTenantId().trim());
        }
        if (!blank(this.properties.getBearerToken())) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION,
                    "Bearer " + this.properties.getBearerToken().trim());
        }
        this.restClient = builder.build();
    }

    @Override
    public String id() {
        return "loki-logs";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean available() {
        return properties.isEnabled();
    }

    @Override
    public LogsResult query(LogsQuery query) {
        String expression = buildExpression(properties.getQueryTemplate(), query.resourceCode());
        return providerGuard.execute(id(), () -> request(query, expression));
    }

    private LogsResult request(LogsQuery query, String expression) {
        int limit = Math.max(1, Math.min(100, query.limit()));
        URI uri = UriComponentsBuilder.fromUriString(normalizeBaseUrl(properties.getBaseUrl()))
                .path("/loki/api/v1/query_range")
                .queryParam("query", expression)
                .queryParam("start", toEpochNanos(query.start()))
                .queryParam("end", toEpochNanos(query.end()))
                .queryParam("limit", limit)
                .queryParam("direction", "backward")
                .build().encode(StandardCharsets.UTF_8).toUri();
        JsonNode body = restClient.get().uri(uri).retrieve().body(JsonNode.class);
        if (body == null || !"success".equals(body.path("status").asText())) {
            throw new IllegalStateException("Loki returned a non-success response");
        }
        if (!"streams".equals(body.path("data").path("resultType").asText())) {
            throw new IllegalStateException("Loki query must return log streams");
        }

        List<LogEntry> entries = new ArrayList<>();
        int redactedFields = 0;
        int streamIndex = 0;
        for (JsonNode streamNode : body.path("data").path("result")) {
            Map<String, String> labels = objectMapper.convertValue(
                    streamNode.path("stream"), new TypeReference<>() {
                    });
            int valueIndex = 0;
            for (JsonNode value : streamNode.path("values")) {
                if (!value.isArray() || value.size() < 2) continue;
                ParsedLine parsed = parseLine(value.get(1).asText(), labels);
                LogRedactor.RedactionResult safe = redactor.redactLog(parsed.message(), parsed.metadata());
                redactedFields += safe.redactedFields();
                String timestamp = value.get(0).asText();
                entries.add(new LogEntry(streamIndex + ":" + valueIndex + ":" + timestamp,
                        parsed.resourceCode(query.resourceCode()), parsed.level(), parsed.loggerName(),
                        safe.message(), parsed.traceId(), safe.metadata(), fromEpochNanos(timestamp)));
                valueIndex++;
            }
            streamIndex++;
        }
        List<LogEntry> sorted = entries.stream()
                .sorted(Comparator.comparing(LogEntry::occurredAt).reversed())
                .limit(limit).toList();
        return new LogsResult(id(), expression, normalizeBaseUrl(properties.getBaseUrl()),
                sorted, redactedFields, List.of());
    }

    private ParsedLine parseLine(String rawLine, Map<String, String> labels) {
        Map<String, String> metadata = new LinkedHashMap<>(labels);
        String message = rawLine;
        String level = first(labels, "level", "severity");
        String loggerName = first(labels, "logger", "logger_name", "app", "job");
        String traceId = first(labels, "trace_id", "traceId");
        String resourceCode = first(labels, "resource_code", "resourceCode", "service_name");
        try {
            JsonNode json = objectMapper.readTree(rawLine);
            if (json.isObject()) {
                message = firstText(json, rawLine, "message", "msg", "log");
                level = firstNonBlank(firstText(json, null, "level", "severity"), level);
                loggerName = firstNonBlank(firstText(json, null, "logger", "logger_name"), loggerName);
                traceId = firstNonBlank(firstText(json, null, "traceId", "trace_id"), traceId);
                resourceCode = firstNonBlank(firstText(json, null, "resourceCode", "resource_code"), resourceCode);
                json.fields().forEachRemaining(entry -> {
                    if (entry.getValue().isValueNode() && !isMessageField(entry.getKey())) {
                        metadata.put(entry.getKey(), entry.getValue().asText());
                    }
                });
            }
        } catch (Exception ignored) {
            // Plain text is a valid Loki log line.
        }
        return new ParsedLine(message, normalizeLevel(level, message),
                firstNonBlank(loggerName, "loki"), traceId, resourceCode, Map.copyOf(metadata));
    }

    static String buildExpression(String template, String resourceCode) {
        String safeResource = resourceCode.replace("\\", "\\\\")
                .replace("\"", "\\\"").replace("\n", "\\n");
        return template.contains("%s") ? template.formatted(safeResource) : template;
    }

    private static long toEpochNanos(LocalDateTime value) {
        Instant instant = value.atZone(ZoneId.systemDefault()).toInstant();
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), NANOS_PER_SECOND), instant.getNano());
    }

    private static LocalDateTime fromEpochNanos(String value) {
        long nanos = Long.parseLong(value);
        long seconds = Math.floorDiv(nanos, NANOS_PER_SECOND);
        long adjustment = Math.floorMod(nanos, NANOS_PER_SECOND);
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(seconds, adjustment), ZoneId.systemDefault());
    }

    private static String normalizeLevel(String value, String message) {
        String candidate = firstNonBlank(value, "").toUpperCase(Locale.ROOT);
        if (!candidate.isBlank()) return candidate;
        String upperMessage = message.toUpperCase(Locale.ROOT);
        if (upperMessage.contains("ERROR") || upperMessage.contains("EXCEPTION")) return "ERROR";
        if (upperMessage.contains("WARN") || upperMessage.contains("TIMEOUT")) return "WARN";
        return "INFO";
    }

    private static String first(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (!blank(value)) return value;
        }
        return null;
    }

    private static String firstText(JsonNode node, String fallback, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) return value.asText();
        }
        return fallback;
    }

    private static String firstNonBlank(String first, String fallback) {
        return blank(first) ? fallback : first;
    }

    private static boolean isMessageField(String key) {
        return "message".equals(key) || "msg".equals(key) || "log".equals(key);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = blank(value) ? "http://localhost:3100" : value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private record ParsedLine(String message, String level, String loggerName, String traceId,
                              String resourceCode, Map<String, String> metadata) {
        private String resourceCode(String fallback) {
            return firstNonBlank(resourceCode, fallback);
        }
    }
}
