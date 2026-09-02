package org.trigger.opspilot.runbook;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "opspilot.runbook.retrieval.lifecycle")
public class RunbookRetrievalLifecycleProperties {
    private Duration retention = Duration.ofDays(30);
    private int cleanupBatchSize = 500;

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        if (retention != null && !retention.isZero() && !retention.isNegative()) {
            this.retention = retention;
        }
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = Math.max(1, Math.min(cleanupBatchSize, 5_000));
    }
}
