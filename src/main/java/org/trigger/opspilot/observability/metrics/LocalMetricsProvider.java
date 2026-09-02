package org.trigger.opspilot.observability.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class LocalMetricsProvider implements MetricsProvider {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public LocalMetricsProvider(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return "local-metrics";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public MetricsResult query(MetricsQuery query) {
        List<MetricSample> samples = jdbcClient.sql("""
                        SELECT sample.id, resource.resource_code, sample.metric_name, sample.metric_value,
                               sample.unit, sample.labels_json, sample.observed_at
                        FROM observability_metric_sample sample
                        JOIN cmdb_resource resource ON resource.id = sample.resource_id
                        WHERE (sample.resource_id = :resourceId
                           OR sample.resource_id IN (
                               SELECT target_resource_id FROM cmdb_relation WHERE source_resource_id = :resourceId
                           )
                           OR sample.resource_id IN (
                               SELECT source_resource_id FROM cmdb_relation WHERE target_resource_id = :resourceId
                           ))
                          AND sample.observed_at BETWEEN :start AND :end
                        ORDER BY sample.observed_at DESC, sample.id DESC LIMIT 50
                        """)
                .param("resourceId", query.resourceId()).param("start", query.start()).param("end", query.end())
                .query((rs, rowNum) -> new MetricSample(
                        String.valueOf(rs.getLong("id")), rs.getString("resource_code"),
                        rs.getString("metric_name"),
                        rs.getBigDecimal("metric_value").stripTrailingZeros().toPlainString(),
                        rs.getString("unit"), parseMap(rs.getString("labels_json")),
                        rs.getObject("observed_at", java.time.LocalDateTime.class))).list();
        String statement = "observability_metric_sample resources=one-hop incident=" + query.incidentId()
                + " window=[" + query.start() + "," + query.end() + "]";
        return new MetricsResult(id(), statement, "db:observability_metric_sample", samples, List.of());
    }

    private Map<String, String> parseMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception exception) {
            return Map.of("parseError", "invalid metadata");
        }
    }
}
