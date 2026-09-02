package org.trigger.opspilot.analytics;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.trigger.opspilot.common.ApiException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
public class IncidentAnalyticsService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> SEVERITIES = Set.of("P1", "P2", "P3", "P4");

    private final JdbcClient jdbcClient;

    public IncidentAnalyticsService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public IncidentAnalyticsView overview(LocalDate from, LocalDate to, String severity) {
        LocalDate effectiveTo = to == null ? LocalDate.now(BUSINESS_ZONE) : to;
        LocalDate effectiveFrom = from == null ? effectiveTo.minusDays(29) : from;
        String normalizedSeverity = severity == null ? "" : severity.trim().toUpperCase();
        validateWindow(effectiveFrom, effectiveTo, normalizedSeverity);

        LocalDateTime start = effectiveFrom.atStartOfDay();
        LocalDateTime endExclusive = effectiveTo.plusDays(1).atStartOfDay();
        List<IncidentMilestone> incidents = jdbcClient.sql("""
                        SELECT incident.id, incident.incident_code, incident.title, incident.severity,
                               resource.name AS resource_name, incident.created_at,
                               incident.acknowledged_at, incident.resolved_at,
                               (SELECT MIN(timeline.created_at)
                                FROM incident_timeline timeline
                                WHERE timeline.incident_id = incident.id
                                  AND timeline.to_status = 'MITIGATED') AS mitigated_at
                        FROM incident
                        JOIN cmdb_resource resource ON resource.id = incident.service_resource_id
                        WHERE incident.created_at >= :start
                          AND incident.created_at < :endExclusive
                          AND (:severity = '' OR incident.severity = :severity)
                        ORDER BY incident.created_at DESC, incident.id DESC
                        """)
                .param("start", start).param("endExclusive", endExclusive)
                .param("severity", normalizedSeverity)
                .query((rs, rowNum) -> new IncidentMilestone(
                        rs.getLong("id"), rs.getString("incident_code"), rs.getString("title"),
                        rs.getString("severity"), rs.getString("resource_name"),
                        rs.getObject("created_at", LocalDateTime.class),
                        rs.getObject("acknowledged_at", LocalDateTime.class),
                        rs.getObject("mitigated_at", LocalDateTime.class),
                        rs.getObject("resolved_at", LocalDateTime.class)))
                .list();

        List<Long> mtta = durations(incidents, Milestone.ACKNOWLEDGED);
        List<Long> mttm = durations(incidents, Milestone.MITIGATED);
        List<Long> mttr = durations(incidents, Milestone.RESOLVED);
        List<SeverityCount> severityDistribution = incidents.stream()
                .map(IncidentMilestone::severity).distinct().sorted()
                .map(value -> new SeverityCount(value, incidents.stream()
                        .filter(item -> value.equals(item.severity())).count()))
                .toList();
        List<SlowIncident> slowestResolved = incidents.stream()
                .filter(item -> validDuration(item.createdAt(), item.resolvedAt()))
                .map(item -> new SlowIncident(item.id(), item.incidentCode(), item.title(),
                        item.severity(), item.resourceName(), item.createdAt(), item.resolvedAt(),
                        minutes(Duration.between(item.createdAt(), item.resolvedAt()).toSeconds())))
                .sorted(Comparator.comparingDouble(SlowIncident::resolutionMinutes).reversed()
                        .thenComparing(SlowIncident::incidentCode))
                .limit(8).toList();

        return new IncidentAnalyticsView(
                new WindowView(effectiveFrom, effectiveTo,
                        normalizedSeverity.isBlank() ? null : normalizedSeverity),
                incidents.size(), metric(mtta), metric(mttm), metric(mttr),
                severityDistribution, slowestResolved, followUpSummary(LocalDate.now(BUSINESS_ZONE)));
    }

    private void validateWindow(LocalDate from, LocalDate to, String severity) {
        if (from.isAfter(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ANALYTICS_INVALID_WINDOW",
                    "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(from, to) > 365) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ANALYTICS_WINDOW_TOO_LARGE",
                    "统计窗口最多包含 366 个自然日");
        }
        if (!severity.isBlank() && !SEVERITIES.contains(severity)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ANALYTICS_INVALID_SEVERITY",
                    "严重等级仅支持 P1、P2、P3、P4");
        }
    }

    private FollowUpSummary followUpSummary(LocalDate asOf) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) AS total,
                               COALESCE(SUM(CASE WHEN status = 'OPEN' THEN 1 ELSE 0 END), 0) AS open_count,
                               COALESCE(SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END), 0) AS done_count,
                               COALESCE(SUM(CASE WHEN status = 'OPEN' AND due_date < :asOf THEN 1 ELSE 0 END), 0) AS overdue_count
                        FROM postmortem_follow_up
                        """).param("asOf", asOf)
                .query((rs, rowNum) -> {
                    long total = rs.getLong("total");
                    long done = rs.getLong("done_count");
                    return new FollowUpSummary(total, rs.getLong("open_count"), done,
                            rs.getLong("overdue_count"), total == 0 ? 0 : round(done * 100.0 / total), asOf);
                }).single();
    }

    private static List<Long> durations(List<IncidentMilestone> incidents, Milestone milestone) {
        List<Long> result = new ArrayList<>();
        for (IncidentMilestone incident : incidents) {
            LocalDateTime reachedAt = switch (milestone) {
                case ACKNOWLEDGED -> incident.acknowledgedAt();
                case MITIGATED -> incident.mitigatedAt();
                case RESOLVED -> incident.resolvedAt();
            };
            if (validDuration(incident.createdAt(), reachedAt)) {
                result.add(Duration.between(incident.createdAt(), reachedAt).toSeconds());
            }
        }
        return result;
    }

    private static boolean validDuration(LocalDateTime createdAt, LocalDateTime reachedAt) {
        return reachedAt != null && !reachedAt.isBefore(createdAt);
    }

    private static DurationMetric metric(List<Long> seconds) {
        if (seconds.isEmpty()) return new DurationMetric(0, null, null);
        List<Long> sorted = seconds.stream().sorted().toList();
        double average = sorted.stream().mapToLong(Long::longValue).average().orElse(0) / 60.0;
        int middle = sorted.size() / 2;
        double medianSeconds = sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
        return new DurationMetric(sorted.size(), round(average), round(medianSeconds / 60.0));
    }

    private static double minutes(long seconds) {
        return round(seconds / 60.0);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private enum Milestone {
        ACKNOWLEDGED, MITIGATED, RESOLVED
    }

    private record IncidentMilestone(long id, String incidentCode, String title, String severity,
                                     String resourceName, LocalDateTime createdAt,
                                     LocalDateTime acknowledgedAt, LocalDateTime mitigatedAt,
                                     LocalDateTime resolvedAt) {
    }

    public record IncidentAnalyticsView(WindowView window, long incidentCount,
                                        DurationMetric mtta, DurationMetric mttm, DurationMetric mttr,
                                        List<SeverityCount> severityDistribution,
                                        List<SlowIncident> slowestResolved,
                                        FollowUpSummary followUps) {
    }

    public record WindowView(LocalDate from, LocalDate to, String severity) {
    }

    public record DurationMetric(int sampleCount, Double averageMinutes, Double medianMinutes) {
    }

    public record SeverityCount(String severity, long count) {
    }

    public record SlowIncident(long id, String incidentCode, String title, String severity,
                               String resourceName, LocalDateTime createdAt,
                               LocalDateTime resolvedAt, double resolutionMinutes) {
    }

    public record FollowUpSummary(long total, long open, long done, long overdue,
                                  double completionRatePercent, LocalDate asOf) {
    }
}
