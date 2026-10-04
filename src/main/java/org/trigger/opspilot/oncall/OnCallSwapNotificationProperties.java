package org.trigger.opspilot.oncall;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "opspilot.oncall.swap.notification")
public record OnCallSwapNotificationProperties(boolean enabled, String url, String token,
        Duration connectTimeout, Duration readTimeout, Duration lease,
        Duration retryBaseDelay, Duration retryMaxDelay, int maxAttempts, int batchSize,
        boolean retentionEnabled, int payloadRetentionDays) {}
