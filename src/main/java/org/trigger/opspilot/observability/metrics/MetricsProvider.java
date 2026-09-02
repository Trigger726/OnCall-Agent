package org.trigger.opspilot.observability.metrics;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface MetricsProvider {
    String id();

    int priority();

    boolean available();

    MetricsResult query(MetricsQuery query);

    record MetricsQuery(long incidentId, long resourceId, String resourceCode, String resourceName,
                        LocalDateTime start, LocalDateTime end) {
    }

    record MetricSample(String ref, String resourceCode, String metricName, String value, String unit,
                        Map<String, String> labels, LocalDateTime observedAt) {
    }

    record MetricsResult(String providerId, String query, String externalRef,
                         List<MetricSample> samples, List<String> warnings) {
        public MetricsResult withWarnings(List<String> value) {
            return new MetricsResult(providerId, query, externalRef, samples, value);
        }
    }
}
