package org.trigger.opspilot.observability;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LogRedactorTest {
    private final LogRedactor redactor = new LogRedactor();

    @Test
    void shouldRedactSecretsAndPersonalIdentifiersFromMessagesAndMetadata() {
        String message = "authorization=Bearer demo-token token=abc123 password=hunter2 "
                + "clientIp=10.20.8.15 user=alice@example.com";

        assertThat(redactor.redact(message))
                .contains("authorization=Bearer ***", "token=***", "password=***")
                .contains("10.20.8.*", "a***@example.com")
                .doesNotContain("demo-token", "abc123", "hunter2", "10.20.8.15", "alice@example.com");

        assertThat(redactor.redact(Map.of(
                "api_key", "sk-demo",
                "owner", "alice@example.com",
                "target", "10.20.8.15")))
                .containsEntry("api_key", "***")
                .containsEntry("owner", "a***@example.com")
                .containsEntry("target", "10.20.8.*");

        LogRedactor.RedactionResult result = redactor.redactLog(
                "token=abc123", Map.of("clientIp", "10.20.8.15", "status", "500"));
        assertThat(result.redactedFields()).isEqualTo(2);
    }
}
