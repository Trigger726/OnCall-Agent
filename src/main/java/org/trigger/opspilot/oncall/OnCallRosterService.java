package org.trigger.opspilot.oncall;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class OnCallRosterService {
    private final JdbcClient jdbcClient;
    private final AuditService auditService;

    public OnCallRosterService(JdbcClient jdbcClient, AuditService auditService) {
        this.jdbcClient = jdbcClient;
        this.auditService = auditService;
    }

    public RosterView roster(Long scheduleId, LocalDateTime from, LocalDateTime to) {
        LocalDateTime now = databaseNow();
        LocalDateTime start = from == null ? now.minusDays(1) : from;
        LocalDateTime end = to == null ? now.plusDays(30) : to;
        if (!end.isAfter(start) || end.isAfter(start.plusDays(31))) {
            throw invalid("查询窗口须为正数且不超过 31 天");
        }
        List<ShiftView> rows = jdbcClient.sql("""
                        SELECT shift.*, schedule.name AS schedule_name, u.display_name AS user_name
                        FROM oncall_shift shift
                        JOIN oncall_schedule schedule ON schedule.id = shift.schedule_id
                        JOIN sys_user u ON u.id = shift.user_id
                        WHERE (:scheduleId IS NULL OR shift.schedule_id = :scheduleId)
                          AND shift.starts_at < :to AND shift.ends_at > :from
                        ORDER BY shift.starts_at, shift.id LIMIT 201
                        """).param("scheduleId", scheduleId).param("from", start).param("to", end)
                .query((rs, row) -> new ShiftView(rs.getLong("id"), rs.getLong("schedule_id"),
                        rs.getString("schedule_name"), rs.getLong("user_id"), rs.getString("user_name"),
                        rs.getObject("starts_at", LocalDateTime.class), rs.getObject("ends_at", LocalDateTime.class),
                        rs.getBoolean("override_flag"), rs.getInt("version"), rs.getString("note"),
                        rs.getObject("cancelled_at", LocalDateTime.class), rs.getString("cancellation_reason")))
                .list();
        List<ScheduleView> schedules = jdbcClient.sql("""
                        SELECT schedule.id, schedule.name, resource.name AS resource_name
                        FROM oncall_schedule schedule JOIN cmdb_resource resource
                          ON resource.id = schedule.service_resource_id
                        WHERE schedule.active = TRUE ORDER BY schedule.id
                        """).query((rs, row) -> new ScheduleView(rs.getLong("id"),
                        rs.getString("name"), rs.getString("resource_name"))).list();
        List<UserView> users = jdbcClient.sql("""
                        SELECT id, display_name, role_code FROM sys_user
                        WHERE status = 'ACTIVE' AND role_code IN ('ADMIN', 'OPS_MANAGER', 'ON_CALL') ORDER BY id
                        """).query((rs, row) -> new UserView(rs.getLong("id"),
                        rs.getString("display_name"), rs.getString("role_code"))).list();
        return new RosterView(now, start, end, now.truncatedTo(ChronoUnit.MINUTES).minusMinutes(1),
                now.plusHours(8).truncatedTo(ChronoUnit.MINUTES), schedules, users,
                rows.stream().limit(200).toList(), rows.size() > 200);
    }

    @Transactional
    public ShiftView create(ShiftCommand command, Long actorId, String sourceIp) {
        if (command.startsAt() == null || command.endsAt() == null
                || !command.endsAt().isAfter(command.startsAt())
                || !command.endsAt().isAfter(databaseNow())
                || command.startsAt().getNano() != 0 || command.endsAt().getNano() != 0
                || command.endsAt().isAfter(command.startsAt().plusDays(31))) {
            throw invalid("排班须按整秒输入；结束须晚于开始与当前数据库时间，单班最长 31 天");
        }
        String note = requireText(command.note());
        if (!lockSchedule(command.scheduleId())) {
            throw new ApiException(HttpStatus.CONFLICT, "ONCALL_SCHEDULE_INACTIVE", "计划已停用");
        }
        boolean eligible = jdbcClient.sql("""
                        SELECT id FROM sys_user WHERE id = :id AND status = 'ACTIVE'
                          AND role_code IN ('ADMIN', 'OPS_MANAGER', 'ON_CALL') FOR UPDATE
                        """).param("id", command.userId()).query(Long.class).optional().isPresent();
        if (!eligible) throw invalid("请选择活跃且有运维职责的用户");
        // The schedule row serializes all roster writes; locking reads see the latest MySQL snapshot.
        boolean overlaps = !jdbcClient.sql("""
                        SELECT id FROM oncall_shift
                        WHERE schedule_id = :scheduleId AND cancelled_at IS NULL
                          AND override_flag = :override AND starts_at < :end AND ends_at > :start
                        FOR UPDATE
                        """).param("scheduleId", command.scheduleId()).param("override", command.override())
                .param("start", command.startsAt()).param("end", command.endsAt())
                .query(Long.class).list().isEmpty();
        if (overlaps) throw new ApiException(HttpStatus.CONFLICT, "ONCALL_SHIFT_OVERLAP",
                "同一计划的同类班次重叠；普通与临时覆盖可重叠，相邻边界可交接");
        var key = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO oncall_shift(schedule_id, user_id, starts_at, ends_at, override_flag, note)
                        VALUES (:scheduleId, :userId, :start, :end, :override, :note)
                        """).param("scheduleId", command.scheduleId()).param("userId", command.userId())
                .param("start", command.startsAt()).param("end", command.endsAt())
                .param("override", command.override()).param("note", note).update(key, "id");
        long id = key.getKey().longValue();
        auditService.recordAs(actorId, sourceIp, "ONCALL_SHIFT_CREATED", "ONCALL_SHIFT", id,
                "计划 " + command.scheduleId() + "，用户 " + command.userId() + "，"
                        + command.startsAt() + " 至 " + command.endsAt()
                        + (command.override() ? "，临时覆盖；" : "，普通班次；") + note);
        return get(id);
    }

    @Transactional
    public ShiftView cancel(long id, int version, String reason, Long actorId, String sourceIp) {
        String explanation = requireText(reason);
        ShiftView initial = get(id);
        lockSchedule(initial.scheduleId());
        int changed = jdbcClient.sql("""
                        UPDATE oncall_shift SET cancelled_at = CURRENT_TIMESTAMP,
                          cancellation_reason = :reason, version = version + 1
                        WHERE id = :id AND version = :version AND cancelled_at IS NULL
                        """).param("id", id).param("version", version).param("reason", explanation).update();
        if (changed == 0) throw new ApiException(HttpStatus.CONFLICT, "ONCALL_SHIFT_VERSION_CONFLICT",
                "班次已变化或已取消，请刷新后核对");
        auditService.recordAs(actorId, sourceIp, "ONCALL_SHIFT_CANCELLED", "ONCALL_SHIFT", id, explanation);
        return get(id);
    }

    private boolean lockSchedule(long id) {
        return jdbcClient.sql("SELECT active FROM oncall_schedule WHERE id = :id FOR UPDATE")
                .param("id", id).query(Boolean.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ONCALL_SCHEDULE_NOT_FOUND", "计划不存在"));
    }

    private ShiftView get(long id) {
        return jdbcClient.sql("""
                        SELECT shift.*, schedule.name AS schedule_name, u.display_name AS user_name
                        FROM oncall_shift shift JOIN oncall_schedule schedule ON schedule.id = shift.schedule_id
                        JOIN sys_user u ON u.id = shift.user_id WHERE shift.id = :id
                        """).param("id", id).query((rs, row) -> new ShiftView(rs.getLong("id"),
                        rs.getLong("schedule_id"), rs.getString("schedule_name"), rs.getLong("user_id"),
                        rs.getString("user_name"), rs.getObject("starts_at", LocalDateTime.class),
                        rs.getObject("ends_at", LocalDateTime.class), rs.getBoolean("override_flag"),
                        rs.getInt("version"), rs.getString("note"),
                        rs.getObject("cancelled_at", LocalDateTime.class), rs.getString("cancellation_reason")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "ONCALL_SHIFT_NOT_FOUND", "班次不存在"));
    }

    private LocalDateTime databaseNow() {
        return jdbcClient.sql("SELECT CURRENT_TIMESTAMP")
                .query((rs, row) -> rs.getObject(1, LocalDateTime.class)).single();
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank() || value.length() > 500) throw invalid("说明须为 1–500 字");
        return value.strip();
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "ONCALL_ROSTER_INVALID", message);
    }

    public record ShiftCommand(long scheduleId, long userId, LocalDateTime startsAt,
                               LocalDateTime endsAt, boolean override, String note) {}
    public record ShiftView(long id, long scheduleId, String scheduleName, long userId, String userName,
                            LocalDateTime startsAt, LocalDateTime endsAt, boolean override, int version,
                            String note, LocalDateTime cancelledAt, String cancellationReason) {}
    public record ScheduleView(long id, String name, String resourceName) {}
    public record UserView(long id, String displayName, String roleCode) {}
    public record RosterView(LocalDateTime databaseNow, LocalDateTime from, LocalDateTime to,
                             LocalDateTime suggestedStart, LocalDateTime suggestedEnd,
                             List<ScheduleView> schedules, List<UserView> users,
                             List<ShiftView> shifts, boolean truncated) {}
}
