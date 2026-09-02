package org.trigger.opspilot.dashboard;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class DashboardService {
    private final JdbcClient jdbcClient;

    public DashboardService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public DashboardView overview() {
        long firingAlerts = count("SELECT COUNT(*) FROM alert_event WHERE status = 'FIRING'");
        long activeIncidents = count("SELECT COUNT(*) FROM incident WHERE status NOT IN ('RESOLVED','CLOSED')");
        long p1Incidents = count("SELECT COUNT(*) FROM incident WHERE severity = 'P1' AND status NOT IN ('RESOLVED','CLOSED')");
        long degradedResources = count("SELECT COUNT(*) FROM cmdb_resource WHERE status <> 'RUNNING'");
        double compression = calculateCompression();
        double mttaMinutes = calculateMtta();
        List<SeverityCount> severity = jdbcClient.sql("""
                        SELECT severity, COUNT(*) AS count FROM incident
                        WHERE status NOT IN ('RESOLVED','CLOSED') GROUP BY severity ORDER BY severity
                        """)
                .query((rs, rowNum) -> new SeverityCount(rs.getString("severity"), rs.getLong("count"))).list();
        List<TrendPoint> trend = jdbcClient.sql("""
                        SELECT CAST(created_at AS DATE) AS incident_day, COUNT(*) AS incident_count FROM incident
                        GROUP BY CAST(created_at AS DATE) ORDER BY incident_day DESC LIMIT 7
                        """)
                .query((rs, rowNum) -> new TrendPoint(
                        rs.getString("incident_day"), rs.getLong("incident_count"))).list();
        List<RiskResource> risks = jdbcClient.sql("""
                        SELECT r.id, r.name, r.resource_type, r.status,
                          (SELECT COUNT(*) FROM incident i WHERE i.service_resource_id = r.id
                           AND i.status NOT IN ('RESOLVED','CLOSED')) AS incidents
                        FROM cmdb_resource r
                        WHERE r.status <> 'RUNNING' OR EXISTS (
                          SELECT 1 FROM incident i WHERE i.service_resource_id = r.id
                          AND i.status NOT IN ('RESOLVED','CLOSED'))
                        ORDER BY incidents DESC, r.name LIMIT 8
                        """)
                .query((rs, rowNum) -> new RiskResource(
                        rs.getLong("id"), rs.getString("name"), rs.getString("resource_type"),
                        rs.getString("status"), rs.getInt("incidents"))).list();
        return new DashboardView(firingAlerts, activeIncidents, p1Incidents, degradedResources,
                compression, mttaMinutes, severity, trend, risks);
    }

    private long count(String sql) {
        return jdbcClient.sql(sql).query(Long.class).single();
    }

    private double calculateCompression() {
        List<Integer> occurrences = jdbcClient.sql("SELECT occurrence_count FROM alert_event")
                .query(Integer.class).list();
        long raw = occurrences.stream().mapToLong(Integer::longValue).sum();
        return raw == 0 ? 0 : Math.round((1.0 - occurrences.size() / (double) raw) * 1000.0) / 10.0;
    }

    private double calculateMtta() {
        List<TimePair> pairs = jdbcClient.sql("""
                        SELECT created_at, acknowledged_at FROM incident WHERE acknowledged_at IS NOT NULL
                        """)
                .query((rs, rowNum) -> new TimePair(
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("acknowledged_at", LocalDateTime.class))).list();
        return pairs.isEmpty() ? 0 : Math.round(pairs.stream()
                .mapToLong(pair -> Duration.between(pair.created(), pair.acknowledged()).toSeconds()).average()
                .orElse(0) / 6.0) / 10.0;
    }

    private record TimePair(LocalDateTime created, LocalDateTime acknowledged) {
    }

    public record DashboardView(long firingAlerts, long activeIncidents, long p1Incidents,
                                long degradedResources, double alertCompressionPercent, double mttaMinutes,
                                List<SeverityCount> severityDistribution, List<TrendPoint> incidentTrend,
                                List<RiskResource> riskResources) {
    }

    public record SeverityCount(String severity, long count) {
    }

    public record TrendPoint(String day, long count) {
    }

    public record RiskResource(Long id, String name, String type, String status, int activeIncidents) {
    }
}
