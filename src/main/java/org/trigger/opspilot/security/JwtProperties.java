package org.trigger.opspilot.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opspilot.security")
public record JwtProperties(String jwtSecret, long accessTokenMinutes) {
}
