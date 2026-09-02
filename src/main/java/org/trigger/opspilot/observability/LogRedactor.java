package org.trigger.opspilot.observability;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class LogRedactor {
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)((?:password|passwd|token|secret|api[_-]?key)\\s*[:=]\\s*)[^\\s,;]+"
    );
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*)(bearer\\s+)?[^\\s,;]+"
    );
    private static final Pattern IPV4 = Pattern.compile(
            "(?<!\\d)(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\.\\d{1,3}(?!\\d)"
    );
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)([a-z0-9._%+-])[a-z0-9._%+-]*@([a-z0-9.-]+\\.[a-z]{2,})"
    );

    public String redact(String value) {
        if (value == null || value.isBlank()) return value;
        String redacted = AUTHORIZATION.matcher(value).replaceAll("$1$2***");
        redacted = SECRET_FIELD.matcher(redacted).replaceAll("$1***");
        redacted = IPV4.matcher(redacted).replaceAll("$1.*");
        return EMAIL.matcher(redacted).replaceAll("$1***@$2");
    }

    public Map<String, String> redact(Map<String, String> metadata) {
        Map<String, String> result = new LinkedHashMap<>();
        metadata.forEach((key, value) -> result.put(key, isSecretKey(key) ? "***" : redact(value)));
        return Map.copyOf(result);
    }

    public RedactionResult redactLog(String message, Map<String, String> metadata) {
        String safeMessage = redact(message);
        Map<String, String> safeMetadata = redact(metadata);
        int redactedFields = java.util.Objects.equals(message, safeMessage) ? 0 : 1;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (!java.util.Objects.equals(entry.getValue(), safeMetadata.get(entry.getKey()))) {
                redactedFields++;
            }
        }
        return new RedactionResult(safeMessage, safeMetadata, redactedFields);
    }

    private static boolean isSecretKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.contains("password") || normalized.contains("token")
                || normalized.contains("secret") || normalized.contains("api_key")
                || normalized.contains("apikey") || normalized.contains("authorization");
    }

    public record RedactionResult(String message, Map<String, String> metadata, int redactedFields) {
    }
}
