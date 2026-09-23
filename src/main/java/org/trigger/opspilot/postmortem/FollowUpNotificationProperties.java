package org.trigger.opspilot.postmortem;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "opspilot.postmortem.follow-up.notification")
public record FollowUpNotificationProperties(
        boolean enabled, String url, String token, Duration connectTimeout,
        Duration readTimeout, Duration retryBaseDelay, Duration retryMaxDelay,
        Duration lease, int maxAttempts, int batchSize) {
}
