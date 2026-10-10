package org.trigger.opspilot.oncall;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class OnCallOpenHandoffService {
    private final JdbcClient jdbc;
    private final OnCallRosterService roster;
    private final AuditService audit;
    private final OnCallPlanMembershipService members;
    private final OnCallOpenHandoffRecipients recipients;

    public OnCallOpenHandoffService(JdbcClient jdbc, OnCallRosterService roster, AuditService audit, OnCallPlanMembershipService members, OnCallOpenHandoffRecipients recipients) {
        this.jdbc = jdbc;
        this.roster = roster;
        this.audit = audit;
        this.members = members;
        this.recipients = recipients;
    }

    public ListView list(Long scheduleId, long actorId, String scope, String status) {
        if (scope == null || !List.of("ALL", "MINE", "AVAILABLE").contains(scope)
                || status != null && !List.of("OPEN", "CLAIMED", "WITHDRAWN").contains(status)) {
            throw invalid("范围须为 ALL/MINE/AVAILABLE，状态须为 OPEN/CLAIMED/WITHDRAWN");
        }
        var snapshot = now();
        // SQL filtering precedes the bounded inbox. AVAILABLE is an observed
        // candidate set, never authority to bypass the claim's locking checks.
        var rows = jdbc.sql("""
                SELECT h.* FROM oncall_open_handoff h
                WHERE (:schedule IS NULL OR h.schedule_id=:schedule)
                  AND (:status IS NULL OR h.status=:status)
                  AND (:scope<>'MINE' OR h.requester_id=:actor OR h.claimed_by=:actor)
                  AND (:scope<>'AVAILABLE' OR (
                    h.status='OPEN' AND h.requester_id<>:actor AND h.ends_at>:snapshot
                    AND EXISTS (SELECT 1 FROM sys_user u WHERE u.id=:actor AND u.status='ACTIVE'
                      AND u.role_code IN ('ADMIN','OPS_MANAGER','ON_CALL'))
                    AND EXISTS (SELECT 1 FROM sys_user u WHERE u.id=h.requester_id AND u.status='ACTIVE'
                      AND u.role_code IN ('ADMIN','OPS_MANAGER','ON_CALL'))
                    AND EXISTS (SELECT 1 FROM oncall_schedule_member m WHERE m.schedule_id=h.schedule_id
                      AND m.user_id=:actor AND m.active=TRUE AND m.can_respond=TRUE)
                    AND EXISTS (SELECT 1 FROM oncall_schedule_member m WHERE m.schedule_id=h.schedule_id
                      AND m.user_id=h.requester_id AND m.active=TRUE AND m.can_respond=TRUE)
                    AND EXISTS (SELECT 1 FROM oncall_schedule s WHERE s.id=h.schedule_id AND s.active=TRUE)
                    AND EXISTS (SELECT 1 FROM oncall_shift s WHERE s.id=h.source_shift_id
                      AND s.schedule_id=h.schedule_id AND s.user_id=h.requester_id AND s.version=h.source_version
                      AND s.override_flag=FALSE AND s.cancelled_at IS NULL
                      AND s.starts_at<=h.starts_at AND s.ends_at>=h.ends_at)
                    AND NOT EXISTS (SELECT 1 FROM oncall_shift s WHERE s.schedule_id=h.schedule_id
                      AND s.id<>h.source_shift_id AND s.cancelled_at IS NULL
                      AND s.starts_at<h.ends_at AND s.ends_at>CASE WHEN h.starts_at>:snapshot THEN h.starts_at ELSE :snapshot END)
                  )) ORDER BY h.id DESC LIMIT 201
                """).param("schedule", scheduleId).param("status", status).param("scope", scope)
                .param("actor", actorId).param("snapshot", snapshot).query(mapper).list();
        return new ListView(snapshot, rows.stream().limit(200).toList(), rows.size() > 200);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View request(Command command, long actorId, String ip) {
        String reason = text(command.reason()), key = key(command.requestKey());
        if (command.sourceShiftId() < 1 || command.sourceVersion() < 0
                || command.startsAt() == null || command.endsAt() == null
                || command.startsAt().getNano() != 0 || command.endsAt().getNano() != 0
                || !command.endsAt().isAfter(command.startsAt())) throw invalid("须提供明确源版本和有效整秒时段");
        var initial = source(command.sourceShiftId());
        lockSchedule(initial.scheduleId());
        lockUser(actorId); // Serializes this publisher's keys across plans.
        var previous = jdbc.sql("SELECT * FROM oncall_open_handoff WHERE requester_id=:actor AND request_key=:key")
                .param("actor", actorId).param("key", key).query(mapper).optional();
        if (previous.isPresent()) {
            var row = previous.get();
            if (row.sourceShiftId() != command.sourceShiftId() || row.sourceVersion() != command.sourceVersion()
                    || !row.startsAt().equals(command.startsAt()) || !row.endsAt().equals(command.endsAt())
                    || !row.reason().equals(reason)) throw conflict("ONCALL_OPEN_HANDOFF_KEY_REUSED", "发布键已用于不同内容");
            return row; // Original publication acknowledgement is not a new assignment.
        }
        requireActiveSchedule(initial.scheduleId());
        var source = source(command.sourceShiftId());
        if (source.userId() != actorId) throw forbidden("只能发布本人普通班次的开放接班请求");
        members.requireResponder(source.scheduleId(), actorId);
        validateSource(source, command.sourceVersion(), command.startsAt(), command.endsAt());
        var generated = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO oncall_open_handoff(schedule_id,source_shift_id,source_version,requester_id,
                  request_key,starts_at,ends_at,reason) VALUES (:schedule,:source,:version,:actor,:key,:start,:end,:reason)
                """).param("schedule", source.scheduleId()).param("source", source.id()).param("version", source.version())
                .param("actor", actorId).param("key", key).param("start", command.startsAt()).param("end", command.endsAt())
                .param("reason", reason).update(generated, "id");
        long id = generated.getKey().longValue();
        audit.recordAs(actorId, ip, "ONCALL_OPEN_HANDOFF_REQUESTED", "ONCALL_OPEN_HANDOFF", id, reason);
        var published = get(id);
        recipients.freeze(published);
        if (!command.endsAt().isAfter(now())) throw expired();
        return published;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CoverageView claim(long id, OperationCommand command, long actorId, String ip) {
        return close(id, command, actorId, ip, "CLAIM");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CoverageView withdraw(long id, OperationCommand command, long actorId, String ip) {
        return close(id, command, actorId, ip, "WITHDRAW");
    }

    private CoverageView close(long id, OperationCommand command, long actorId, String ip, String kind) {
        String reason = text(command.reason()), key = key(command.operationKey());
        if (command.version() < 0) throw invalid("须提供明确请求版本");
        var initial = get(id);
        lockSchedule(initial.scheduleId());
        var row = get(id, true);
        if (kind.equals("WITHDRAW") && actorId != row.requesterId()) throw forbidden("只有发布本人能撤回请求");
        if (kind.equals("CLAIM") && actorId == row.requesterId()) throw forbidden("不能认领自己发布的请求");
        // Closed receipts require the caller's current qualification, not the
        // former owner's still being available. New responsibility checks both.
        if (kind.equals("CLAIM") && row.status().equals("OPEN")) {
            lockUser(Math.min(actorId, row.requesterId()));
            lockUser(Math.max(actorId, row.requesterId()));
        } else lockUser(actorId);
        var previous = jdbc.sql("SELECT * FROM oncall_open_handoff_operation WHERE actor_id=:actor AND operation_key=:key")
                .param("actor", actorId).param("key", key).query(operationMapper).optional();
        if (previous.isPresent()) {
            var saved = previous.get();
            if (saved.handoffId() != id || !saved.operation().equals(kind) || saved.capturedVersion() != command.version()
                    || !saved.reason().equals(reason)) throw conflict("ONCALL_OPEN_HANDOFF_OPERATION_KEY_REUSED", "操作键已用于不同请求或内容");
            return coverage(id);
        }
        if (!row.status().equals("OPEN") || row.version() != command.version() || row.version() == Integer.MAX_VALUE) {
            throw conflict("ONCALL_OPEN_HANDOFF_VERSION_CONFLICT", "请求已变化，不更新原版本或代替他人认领");
        }
        Long replacement = null;
        if (kind.equals("CLAIM")) {
            requireActiveSchedule(row.scheduleId());
            members.requireResponder(row.scheduleId(), actorId);
            members.requireResponder(row.scheduleId(), row.requesterId());
            var original = source(row.sourceShiftId());
            if (original.scheduleId() != row.scheduleId() || original.userId() != row.requesterId()) {
                throw conflict("ONCALL_OPEN_HANDOFF_SOURCE_CHANGED", "原计划或负责人已变化");
            }
            var current = now();
            var nextSecond = current.getNano() == 0 ? current : current.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
            var start = row.startsAt().isAfter(nextSecond) ? row.startsAt() : nextSecond;
            validateSource(original, row.sourceVersion(), start, row.endsAt());
            replacement = roster.create(new OnCallRosterService.ShiftCommand(row.scheduleId(), actorId, start,
                    row.endsAt(), true, "开放接班请求 #" + id), actorId, ip).id();
        }
        var committedAt = now();
        jdbc.sql("""
                UPDATE oncall_open_handoff SET status=:status,version=version+1,closed_at=:at,
                  claimed_by=:actor,replacement_shift_id=:replacement WHERE id=:id
                """).param("id", id).param("status", kind.equals("CLAIM") ? "CLAIMED" : "WITHDRAWN")
                .param("at", committedAt).param("actor", kind.equals("CLAIM") ? actorId : null)
                .param("replacement", replacement).update();
        jdbc.sql("""
                INSERT INTO oncall_open_handoff_operation(handoff_id,actor_id,operation_key,operation,captured_version,reason,committed_at)
                VALUES (:id,:actor,:key,:operation,:version,:reason,:at)
                """).param("id", id).param("actor", actorId).param("key", key).param("operation", kind)
                .param("version", command.version()).param("reason", reason).param("at", committedAt).update();
        audit.recordAs(actorId, ip, kind.equals("CLAIM") ? "ONCALL_OPEN_HANDOFF_CLAIMED" : "ONCALL_OPEN_HANDOFF_WITHDRAWN",
                "ONCALL_OPEN_HANDOFF", id, reason);
        if (kind.equals("CLAIM") && !row.endsAt().isAfter(now())) throw expired();
        return coverage(id);
    }

    public View get(long id) { return get(id, false); }

    public CoverageView coverage(long id) {
        // History, actual replacement cancellation and operation receipt are one
        // SQL snapshot. CLAIMED never means the replacement is currently active.
        return jdbc.sql("""
                SELECT h.*,CURRENT_TIMESTAMP(6) AS database_now,
                  s.id AS coverage_id,s.schedule_id AS coverage_schedule,s.user_id AS coverage_user,
                  s.version AS coverage_version,s.starts_at AS coverage_start,s.ends_at AS coverage_end,
                  s.cancelled_at AS coverage_cancelled,s.cancellation_reason AS coverage_cancel_reason,
                  o.actor_id AS operation_actor,o.operation_key,o.operation,o.captured_version,
                  o.reason AS operation_reason,o.committed_at AS operation_committed
                FROM oncall_open_handoff h LEFT JOIN oncall_shift s ON s.id=h.replacement_shift_id
                  LEFT JOIN oncall_open_handoff_operation o ON o.handoff_id=h.id WHERE h.id=:id
                """).param("id", id).query((rs, n) -> new CoverageView(rs.getObject("database_now", LocalDateTime.class),
                        mapper.mapRow(rs, n), rs.getObject("coverage_id", Long.class) == null ? null : new Replacement(
                        rs.getLong("coverage_id"), rs.getLong("coverage_schedule"), rs.getLong("coverage_user"), rs.getInt("coverage_version"),
                        rs.getObject("coverage_start", LocalDateTime.class), rs.getObject("coverage_end", LocalDateTime.class),
                        rs.getObject("coverage_cancelled", LocalDateTime.class), rs.getString("coverage_cancel_reason")),
                        rs.getObject("operation_actor", Long.class) == null ? null : new Operation(id, rs.getLong("operation_actor"),
                        rs.getString("operation_key"), rs.getString("operation"), rs.getInt("captured_version"),
                        rs.getString("operation_reason"), rs.getObject("operation_committed", LocalDateTime.class))))
                .optional().orElseThrow(() -> missing("开放接班请求不存在"));
    }

    private void validateSource(Source source, int version, LocalDateTime start, LocalDateTime end) {
        if (source.override() || source.cancelledAt() != null || source.version() != version) {
            throw conflict("ONCALL_OPEN_HANDOFF_SOURCE_CHANGED", "原班次不是有效的捕获版本普通班次");
        }
        if (start.isBefore(source.startsAt()) || end.isAfter(source.endsAt())) throw invalid("开放时段须在原班次内");
        if (!end.isAfter(start) || !end.isAfter(now())) throw expired();
        boolean overlap = !jdbc.sql("""
                SELECT id FROM oncall_shift WHERE schedule_id=:schedule AND id<>:id AND cancelled_at IS NULL
                  AND starts_at<:end AND ends_at>:start FOR UPDATE
                """).param("schedule", source.scheduleId()).param("id", source.id()).param("start", start)
                .param("end", end).query(Long.class).list().isEmpty();
        if (overlap) throw conflict("ONCALL_OPEN_HANDOFF_OVERLAP", "剩余时段存在其他班次或临时覆盖，请先核对");
    }

    private View get(long id, boolean lock) {
        return jdbc.sql("SELECT * FROM oncall_open_handoff WHERE id=:id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(mapper).optional().orElseThrow(() -> missing("开放接班请求不存在"));
    }
    private Source source(long id) {
        return jdbc.sql("SELECT * FROM oncall_shift WHERE id=:id").param("id", id).query((rs, n) -> new Source(
                rs.getLong("id"), rs.getLong("schedule_id"), rs.getLong("user_id"), rs.getInt("version"),
                rs.getBoolean("override_flag"), rs.getObject("starts_at", LocalDateTime.class),
                rs.getObject("ends_at", LocalDateTime.class), rs.getObject("cancelled_at", LocalDateTime.class)))
                .optional().orElseThrow(() -> missing("原班次不存在"));
    }
    private void lockSchedule(long id) {
        jdbc.sql("SELECT active FROM oncall_schedule WHERE id=:id FOR UPDATE").param("id", id).query(Boolean.class)
                .optional().orElseThrow(() -> missing("计划不存在"));
    }
    private void requireActiveSchedule(long id) {
        if (!jdbc.sql("SELECT active FROM oncall_schedule WHERE id=:id").param("id", id).query(Boolean.class).single()) {
            throw conflict("ONCALL_SCHEDULE_INACTIVE", "计划已停用");
        }
    }
    private void lockUser(long id) {
        if (jdbc.sql("SELECT id FROM sys_user WHERE id=:id AND status='ACTIVE' AND role_code IN ('ADMIN','OPS_MANAGER','ON_CALL') FOR UPDATE")
                .param("id", id).query(Long.class).optional().isEmpty()) throw forbidden("当前账号须活跃且具有运维职责");
    }
    private LocalDateTime now() {
        return jdbc.sql("SELECT CURRENT_TIMESTAMP(6)").query((rs, n) -> rs.getObject(1, LocalDateTime.class)).single();
    }
    private static String text(String value) {
        if (value == null || value.isBlank() || value.length() > 500) throw invalid("说明须为1–500字");
        return value.strip();
    }
    private static String key(String value) {
        try { var canonical = UUID.fromString(value).toString(); if (!canonical.equals(value)) throw new IllegalArgumentException(); return canonical; }
        catch (NullPointerException | IllegalArgumentException e) { throw invalid("操作键须为规范UUID"); }
    }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "ONCALL_OPEN_HANDOFF_INVALID", message); }
    private static ApiException forbidden(String message) { return new ApiException(HttpStatus.FORBIDDEN, "ONCALL_OPEN_HANDOFF_FORBIDDEN", message); }
    private static ApiException missing(String message) { return new ApiException(HttpStatus.NOT_FOUND, "ONCALL_OPEN_HANDOFF_NOT_FOUND", message); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private static ApiException expired() { return conflict("ONCALL_OPEN_HANDOFF_EXPIRED", "可认领剩余时段已结束"); }
    private static final RowMapper<View> mapper = (rs, n) -> new View(rs.getLong("id"), rs.getLong("schedule_id"),
            rs.getLong("source_shift_id"), rs.getInt("source_version"), rs.getLong("requester_id"), rs.getString("request_key"),
            rs.getObject("starts_at", LocalDateTime.class), rs.getObject("ends_at", LocalDateTime.class), rs.getString("reason"),
            rs.getString("status"), rs.getInt("version"), rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("closed_at", LocalDateTime.class), rs.getObject("claimed_by", Long.class), rs.getObject("replacement_shift_id", Long.class));
    private static final RowMapper<Operation> operationMapper = (rs, n) -> new Operation(rs.getLong("handoff_id"),
            rs.getLong("actor_id"), rs.getString("operation_key"), rs.getString("operation"), rs.getInt("captured_version"),
            rs.getString("reason"), rs.getObject("committed_at", LocalDateTime.class));
    private record Source(long id, long scheduleId, long userId, int version, boolean override,
                          LocalDateTime startsAt, LocalDateTime endsAt, LocalDateTime cancelledAt) {}
    public record Command(long sourceShiftId, int sourceVersion, String requestKey, LocalDateTime startsAt, LocalDateTime endsAt, String reason) {}
    public record OperationCommand(int version, String operationKey, String reason) {}
    public record View(long id, long scheduleId, long sourceShiftId, int sourceVersion, long requesterId, String requestKey,
                       LocalDateTime startsAt, LocalDateTime endsAt, String reason, String status, int version,
                       LocalDateTime createdAt, LocalDateTime closedAt, Long claimedBy, Long replacementShiftId) {}
    public record ListView(LocalDateTime databaseNow, List<View> requests, boolean truncated) {}
    public record Replacement(long id, long scheduleId, long userId, int version, LocalDateTime startsAt, LocalDateTime endsAt,
                              LocalDateTime cancelledAt, String cancellationReason) {}
    public record Operation(long handoffId, long actorId, String operationKey, String operation, int capturedVersion, String reason, LocalDateTime committedAt) {}
    public record CoverageView(LocalDateTime databaseNow, View request, Replacement replacement, Operation operation) {}
}
