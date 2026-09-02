package org.trigger.opspilot.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "opspilot.observability")
public class ObservabilityProperties {
    private Reliability reliability = new Reliability();
    private Prometheus prometheus = new Prometheus();
    private Loki loki = new Loki();

    public Reliability getReliability() {
        return reliability;
    }

    public void setReliability(Reliability reliability) {
        this.reliability = reliability;
    }

    public Prometheus getPrometheus() {
        return prometheus;
    }

    public void setPrometheus(Prometheus prometheus) {
        this.prometheus = prometheus;
    }

    public Loki getLoki() {
        return loki;
    }

    public void setLoki(Loki loki) {
        this.loki = loki;
    }

    public static class Reliability {
        private int maxAttempts = 2;
        private Duration backoff = Duration.ofMillis(150);
        private int failureThreshold = 3;
        private Duration openDuration = Duration.ofSeconds(30);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = Math.max(1, maxAttempts);
        }

        public Duration getBackoff() {
            return backoff;
        }

        public void setBackoff(Duration backoff) {
            this.backoff = backoff == null ? Duration.ZERO : backoff;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = Math.max(1, failureThreshold);
        }

        public Duration getOpenDuration() {
            return openDuration;
        }

        public void setOpenDuration(Duration openDuration) {
            this.openDuration = openDuration == null ? Duration.ofSeconds(30) : openDuration;
        }
    }

    public static class Prometheus {
        private boolean enabled;
        private String baseUrl = "http://localhost:9090";
        private String queryTemplate = "up{job=\"%s\"}";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(4);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getQueryTemplate() {
            return queryTemplate;
        }

        public void setQueryTemplate(String queryTemplate) {
            this.queryTemplate = queryTemplate;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    public static class Loki {
        private boolean enabled;
        private String baseUrl = "http://localhost:3100";
        private String queryTemplate = "{resource_code=\"%s\"}";
        private String tenantId = "";
        private String bearerToken = "";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getQueryTemplate() {
            return queryTemplate;
        }

        public void setQueryTemplate(String queryTemplate) {
            this.queryTemplate = queryTemplate;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getBearerToken() {
            return bearerToken;
        }

        public void setBearerToken(String bearerToken) {
            this.bearerToken = bearerToken;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }
}
