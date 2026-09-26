package org.trigger.opspilot.oncall;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.common.ApiException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/** Read-only effective coverage of persisted shifts, not a forecast of ungenerated rotations. */
@Service
public class OnCallCoverageService {
    private final JdbcClient jdbc;
    public OnCallCoverageService(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CoverageView coverage(long scheduleId, LocalDateTime from, LocalDateTime to) {
        LocalDateTime now = jdbc.sql("SELECT CURRENT_TIMESTAMP")
                .query((rs, row) -> rs.getObject(1, LocalDateTime.class)).single();
        LocalDateTime start = from == null ? now.truncatedTo(ChronoUnit.SECONDS) : from;
        LocalDateTime end = to == null ? start.plusDays(7) : to;
        if (!end.isAfter(start) || end.isAfter(start.plusDays(31))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ONCALL_COVERAGE_INVALID", "覆盖窗口须为正数且不超过 31 天");
        }
        Schedule schedule = jdbc.sql("""
                SELECT s.id, s.name, s.active, r.name AS resource_name FROM oncall_schedule s
                JOIN cmdb_resource r ON r.id = s.service_resource_id WHERE s.id = :id
                """).param("id", scheduleId).query((rs, row) -> new Schedule(rs.getLong("id"),
                rs.getString("name"), rs.getString("resource_name"), rs.getBoolean("active"))).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ONCALL_SCHEDULE_NOT_FOUND", "计划不存在"));
        List<Shift> shifts = jdbc.sql("""
                SELECT s.id, s.user_id, u.display_name, s.starts_at, s.ends_at, s.override_flag,
                  CASE WHEN u.status = 'ACTIVE' AND u.role_code IN ('ADMIN','OPS_MANAGER','ON_CALL')
                    THEN TRUE ELSE FALSE END AS eligible
                FROM oncall_shift s JOIN sys_user u ON u.id = s.user_id
                WHERE s.schedule_id = :id AND s.cancelled_at IS NULL
                  AND s.starts_at < :to AND s.ends_at > :from
                ORDER BY s.starts_at, s.id LIMIT 1001
                """).param("id", scheduleId).param("from", start).param("to", end)
                .query((rs, row) -> new Shift(rs.getLong("id"), rs.getLong("user_id"), rs.getString("display_name"),
                        rs.getObject("starts_at", LocalDateTime.class), rs.getObject("ends_at", LocalDateTime.class),
                        rs.getBoolean("override_flag"), rs.getBoolean("eligible"))).list();
        if (shifts.size() > 1000) {
            // A truncated roster must never imply complete/healthy coverage.
            throw new ApiException(HttpStatus.CONFLICT, "ONCALL_COVERAGE_TOO_DENSE", "窗口超过 1000 条未取消班次；请缩小范围，不返回不完整覆盖结论");
        }
        TreeSet<LocalDateTime> points = new TreeSet<>(List.of(start, end));
        for (Shift shift : shifts) {
            points.add(shift.startsAt().isBefore(start) ? start : shift.startsAt());
            points.add(shift.endsAt().isAfter(end) ? end : shift.endsAt());
        }
        List<LocalDateTime> boundaries = List.copyOf(points);
        List<Segment> segments = new ArrayList<>();
        // Matches current() and ON_CALL routing: override, latest start, latest id; half-open intervals.
        Comparator<Shift> priority = Comparator.comparing(Shift::override)
                .thenComparing(Shift::startsAt).thenComparingLong(Shift::id).reversed();
        long coveredNanos = 0;
        for (int i = 0; i < boundaries.size() - 1; i++) {
            LocalDateTime left = boundaries.get(i), right = boundaries.get(i + 1);
            List<Shift> present = shifts.stream().filter(s -> !s.startsAt().isAfter(left) && s.endsAt().isAfter(left)).toList();
            List<Shift> eligible = present.stream().filter(Shift::eligible).sorted(priority).toList();
            Shift winner = schedule.active() && !eligible.isEmpty() ? eligible.get(0) : null;
            List<Long> unavailable = present.stream().filter(s -> !s.eligible()).map(Shift::id).sorted().toList();
            List<Long> shadowed = eligible.stream().filter(s -> winner == null || s.id() != winner.id()).map(Shift::id).toList();
            boolean overlap = eligible.stream().filter(s -> !s.override()).count() > 1
                    || eligible.stream().filter(Shift::override).count() > 1;
            String reason = winner != null ? null : !schedule.active() ? "SCHEDULE_INACTIVE"
                    : present.isEmpty() ? "NO_SHIFT" : "MEMBER_UNAVAILABLE";
            if (winner != null) coveredNanos += Duration.between(left, right).toNanos();
            Segment next = new Segment(left, right, winner == null ? null : winner.id(),
                    winner == null ? null : winner.userId(), winner == null ? null : winner.userName(),
                    winner != null && winner.override(), reason, shadowed, unavailable, overlap);
            if (!segments.isEmpty() && sameOwnerAndDiagnostics(segments.get(segments.size() - 1), next)) {
                Segment prior = segments.remove(segments.size() - 1);
                next = new Segment(prior.startsAt(), right, next.shiftId(), next.userId(), next.userName(),
                        next.override(), reason, shadowed, unavailable, overlap);
            }
            segments.add(next);
        }
        long totalNanos = Duration.between(start, end).toNanos();
        return new CoverageView(now, start, end, schedule, shifts.size(), totalNanos / 1e9,
                coveredNanos / 1e9, (totalNanos - coveredNanos) / 1e9, List.copyOf(segments));
    }

    private static boolean sameOwnerAndDiagnostics(Segment a, Segment b) {
        return java.util.Objects.equals(a.shiftId(), b.shiftId()) && java.util.Objects.equals(a.gapReason(), b.gapReason())
                && a.shadowedShiftIds().equals(b.shadowedShiftIds()) && a.unavailableShiftIds().equals(b.unavailableShiftIds())
                && a.sameLayerOverlap() == b.sameLayerOverlap();
    }
    private record Shift(long id, long userId, String userName, LocalDateTime startsAt, LocalDateTime endsAt,
                         boolean override, boolean eligible) {}
    public record Schedule(long id, String name, String resourceName, boolean active) {}
    public record Segment(LocalDateTime startsAt, LocalDateTime endsAt, Long shiftId, Long userId, String userName,
                          boolean override, String gapReason, List<Long> shadowedShiftIds,
                          List<Long> unavailableShiftIds, boolean sameLayerOverlap) {}
    public record CoverageView(LocalDateTime databaseNow, LocalDateTime from, LocalDateTime to, Schedule schedule,
                               int sourceShifts, double totalSeconds, double coveredSeconds, double gapSeconds,
                               List<Segment> segments) {}
}
