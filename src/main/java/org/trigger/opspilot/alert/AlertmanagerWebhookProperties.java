package org.trigger.opspilot.alert;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "opspilot.alertmanager.webhook")
public class AlertmanagerWebhookProperties {
    private String secret = "disabled";
    private int maxAlerts = 100;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret == null ? "disabled" : secret.trim();
    }

    public int getMaxAlerts() {
        return maxAlerts;
    }

    public void setMaxAlerts(int maxAlerts) {
        this.maxAlerts = Math.max(1, Math.min(1000, maxAlerts));
    }

    public boolean enabled() {
        return !secret.isBlank() && !"disabled".equalsIgnoreCase(secret);
    }
}
