package org.trigger.opspilot.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "opspilot.alertmanager.rejections")
public class AlertmanagerRejectionProperties {
    private boolean autoReplayEnabled;
    private Duration replayLease = Duration.ofSeconds(30);
    private Duration retryBaseDelay = Duration.ofMinutes(1);
    private Duration retryMaxDelay = Duration.ofHours(1);
    private int maxAutoReplayAttempts = 5;
    private int autoReplayBatchSize = 20;
    private Duration payloadRetention = Duration.ofDays(3);
    private int cleanupBatchSize = 500;

    public boolean isAutoReplayEnabled() {
        return autoReplayEnabled;
    }

    public void setAutoReplayEnabled(boolean autoReplayEnabled) {
        this.autoReplayEnabled = autoReplayEnabled;
    }

    public Duration getReplayLease() {
        return replayLease;
    }

    public void setReplayLease(Duration replayLease) {
        this.replayLease = positive(replayLease, this.replayLease);
    }

    public Duration getRetryBaseDelay() {
        return retryBaseDelay;
    }

    public void setRetryBaseDelay(Duration retryBaseDelay) {
        this.retryBaseDelay = positive(retryBaseDelay, this.retryBaseDelay);
    }

    public Duration getRetryMaxDelay() {
        return retryMaxDelay;
    }

    public void setRetryMaxDelay(Duration retryMaxDelay) {
        this.retryMaxDelay = positive(retryMaxDelay, this.retryMaxDelay);
    }

    public int getMaxAutoReplayAttempts() {
        return maxAutoReplayAttempts;
    }

    public void setMaxAutoReplayAttempts(int maxAutoReplayAttempts) {
        this.maxAutoReplayAttempts = Math.max(1, Math.min(20, maxAutoReplayAttempts));
    }

    public int getAutoReplayBatchSize() {
        return autoReplayBatchSize;
    }

    public void setAutoReplayBatchSize(int autoReplayBatchSize) {
        this.autoReplayBatchSize = Math.max(1, Math.min(100, autoReplayBatchSize));
    }

    public Duration getPayloadRetention() {
        return payloadRetention;
    }

    public void setPayloadRetention(Duration payloadRetention) {
        this.payloadRetention = positive(payloadRetention, this.payloadRetention);
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = Math.max(1, Math.min(5_000, cleanupBatchSize));
    }

    private static Duration positive(Duration candidate, Duration fallback) {
        return candidate == null || candidate.isZero() || candidate.isNegative() ? fallback : candidate;
    }
}
