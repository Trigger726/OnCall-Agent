package org.trigger.opspilot.observability.logs;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LogsProviderRouterTest {
    @Test
    void shouldFallBackAndPreserveExternalProviderFailureAsWarning() {
        LogsProvider external = provider("loki-logs", 100, query -> {
            throw new IllegalStateException("upstream timeout");
        });
        LogsProvider local = provider("local-logs", 10, query -> new LogsProvider.LogsResult(
                "local-logs", "local-query", "db:logs", List.of(), 0, List.of()));
        LogsProviderRouter router = new LogsProviderRouter(List.of(local, external));
        LocalDateTime now = LocalDateTime.of(2026, 8, 31, 18, 0);

        LogsProvider.LogsResult result = router.query(new LogsProvider.LogsQuery(
                1, 1, "APP-SETTLEMENT", "统一结算服务", now.minusMinutes(30), now, 30));

        assertThat(result.providerId()).isEqualTo("local-logs");
        assertThat(result.warnings()).singleElement()
                .isEqualTo("loki-logs unavailable (IllegalStateException)");
    }

    private static LogsProvider provider(String id, int priority, QueryFunction queryFunction) {
        return new LogsProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public LogsResult query(LogsQuery query) {
                return queryFunction.query(query);
            }
        };
    }

    @FunctionalInterface
    private interface QueryFunction {
        LogsProvider.LogsResult query(LogsProvider.LogsQuery query);
    }
}
