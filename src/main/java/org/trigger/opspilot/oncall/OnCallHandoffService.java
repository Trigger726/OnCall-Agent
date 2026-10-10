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
public class OnCallHandoffService {
    private final JdbcClient jdbc;
    private final OnCallRosterService roster;
    private final AuditService audit;
    private final OnCallPlanMembershipService members;

    public OnCallHandoffService(JdbcClient jdbc, OnCallRosterService roster, AuditService audit, OnCallPlanMembershipService members) {
        this.jdbc = jdbc;
        this.roster = roster;
        this.audit = audit;
        this.members = members;
    }

    public ListView list(Long scheduleId) {
        return list(scheduleId, null, null);
    }

    public ListView list(Long scheduleId, Long participantId, String status) {
        if (status != null && !List.of("PENDING", "ACCEPTED", "REJECTED", "WITHDRAWN").contains(status)) {
            throw invalid("状态须为 PENDING、ACCEPTED、REJECTED 或 WITHDRAWN；查询全部时省略状态");
        }
        var now = now();
        // Filter in SQL before the bounded result, otherwise newer unrelated requests
        // can hide an older incoming/outgoing task from the participant's inbox.
        var rows = jdbc.sql("""
                SELECT * FROM oncall_handoff
                WHERE (:schedule IS NULL OR schedule_id=:schedule)
                  AND (:participant IS NULL OR requester_id=:participant OR target_user_id=:participant)
                  AND (:status IS NULL OR status=:status)
                ORDER BY id DESC LIMIT 201
                """).param("schedule", scheduleId).param("participant", participantId)
                .param("status", status).query(mapper).list();
        return new ListView(now, rows.stream().limit(200).toList(), rows.size() > 200);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View request(Command command, long actorId, String ip) {
        String reason = text(command.reason());
        String key;
        try {
            key = UUID.fromString(command.requestKey()).toString();
            if (!key.equals(command.requestKey())) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalid("请求键须为规范 UUID");
        }
        if (command.sourceVersion() < 0 || command.startsAt() == null || command.endsAt() == null
                || command.startsAt().getNano() != 0 || command.endsAt().getNano() != 0
                || !command.endsAt().isAfter(command.startsAt())) throw invalid("请提供有效版本和整秒时段");
        var initial = source(command.sourceShiftId());
        lockSchedule(initial.scheduleId());
        // Same lock order as roster/rotation writes. Sorted user locks also serialize a
        // requester's idempotency keys across different schedules without duplicate recovery.
        lockUsers(actorId, command.targetUserId());
        var existing = jdbc.sql("SELECT * FROM oncall_handoff WHERE requester_id=:actor AND request_key=:key")
                .param("actor", actorId).param("key", key).query(mapper).optional();
        if (existing.isPresent()) {
            View row = existing.get();
            if (row.sourceShiftId() != command.sourceShiftId() || row.sourceVersion() != command.sourceVersion()
                    || row.targetUserId() != command.targetUserId() || !row.startsAt().equals(command.startsAt())
                    || !row.endsAt().equals(command.endsAt()) || !row.reason().equals(reason)) {
                throw conflict("ONCALL_HANDOFF_KEY_REUSED", "请求键已用于不同内容");
            }
            return row;
        }
        if (actorId == command.targetUserId()) throw invalid("不能向本人申请接班");
        requireActiveSchedule(initial.scheduleId());
        var source = source(command.sourceShiftId());
        if (source.userId() != actorId) throw forbidden("只能申请自己的普通班次");
        validateSource(source, command.sourceVersion(), command.startsAt(), command.endsAt());
        members.requireResponder(source.scheduleId(), actorId);
        members.requireResponder(source.scheduleId(), command.targetUserId());
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO oncall_handoff(schedule_id,source_shift_id,source_version,requester_id,
                  target_user_id,request_key,starts_at,ends_at,reason)
                VALUES (:schedule,:source,:version,:actor,:target,:key,:start,:end,:reason)
                """).param("schedule", source.scheduleId()).param("source", source.id())
                .param("version", source.version()).param("actor", actorId).param("target", command.targetUserId())
                .param("key", key).param("start", command.startsAt()).param("end", command.endsAt())
                .param("reason", reason).update(keyHolder, "id");
        long id = keyHolder.getKey().longValue();
        audit.recordAs(actorId, ip, "ONCALL_HANDOFF_REQUESTED", "ONCALL_HANDOFF", id, reason);
        return get(id, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View decide(long id, Decision decision, long actorId, String ip) {
        String reason = text(decision.reason());
        if (decision.status() == null || !List.of("ACCEPTED", "REJECTED", "WITHDRAWN").contains(decision.status()) || decision.version() < 0) {
            throw invalid("决策须为 ACCEPTED、REJECTED 或 WITHDRAWN，并提供有效版本");
        }
        View initial = get(id, false);
        lockSchedule(initial.scheduleId());
        View row = get(id, true);
        // Withdrawal/rejection remain available for stale source shifts or inactive plans.
        long expectedActor = decision.status().equals("WITHDRAWN") ? row.requesterId() : row.targetUserId();
        if (actorId != expectedActor) throw forbidden("仅申请人可撤回，仅指定接班人可接受或拒绝");
        if (decision.status().equals("ACCEPTED") && row.status().equals("PENDING")) {
            lockUsers(row.requesterId(), row.targetUserId());
        } else {
            lockUser(actorId);
        }
        if (row.status().equals(decision.status()) && row.version() == decision.version() + 1
                && reason.equals(row.decisionReason())) return row;
        if (!row.status().equals("PENDING") || row.version() != decision.version()) {
            throw conflict("ONCALL_HANDOFF_VERSION_CONFLICT", "请求已变化，请刷新后核对");
        }
        Long replacement = null;
        if (decision.status().equals("ACCEPTED")) {
            requireActiveSchedule(row.scheduleId());
            members.requireResponder(row.scheduleId(), row.requesterId());
            members.requireResponder(row.scheduleId(), row.targetUserId());
            // Both participants must still be eligible when responsibility changes.
            var source = source(row.sourceShiftId());
            if (source.userId() != row.requesterId()) throw conflict("ONCALL_HANDOFF_SOURCE_CHANGED", "原负责人已变化");
            LocalDateTime start = row.startsAt();
            LocalDateTime current = now();
            LocalDateTime nextSecond = current.getNano() == 0 ? current : current.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
            if (start.isBefore(nextSecond)) start = nextSecond;
            validateSource(source, row.sourceVersion(), start, row.endsAt());
            replacement = roster.create(new OnCallRosterService.ShiftCommand(row.scheduleId(), row.targetUserId(),
                    start, row.endsAt(), true, "接班请求 #" + row.id()), actorId, ip).id();
        }
        jdbc.sql("""
                UPDATE oncall_handoff SET status=:status,version=version+1,decided_at=:at,
                  decided_by=:actor,decision_reason=:reason,replacement_shift_id=:replacement WHERE id=:id
                """).param("id", id).param("status", decision.status()).param("actor", actorId)
                .param("reason", reason).param("replacement", replacement).param("at", now().truncatedTo(ChronoUnit.SECONDS)).update();
        audit.recordAs(actorId, ip, "ONCALL_HANDOFF_" + decision.status(), "ONCALL_HANDOFF", id, reason);
        return get(id, false);
    }

    private void validateSource(Source source, int version, LocalDateTime start, LocalDateTime end) {
        if (source.override() || source.cancelledAt() != null || source.version() != version) {
            throw conflict("ONCALL_HANDOFF_SOURCE_CHANGED", "原班次已取消、版本变化或为临时覆盖");
        }
        if (start.isBefore(source.startsAt()) || end.isAfter(source.endsAt())) throw invalid("申请时段须包含于原班次");
        if (!end.isAfter(start) || !end.isAfter(now())) throw conflict("ONCALL_HANDOFF_EXPIRED", "可接班时段已结束");
        // Legacy overlapping ordinary rows must not let an old owner displace the current winner.
        boolean ambiguous = !jdbc.sql("""
                SELECT id FROM oncall_shift WHERE schedule_id=:schedule AND cancelled_at IS NULL
                  AND id<>:id AND starts_at<:end AND ends_at>:start FOR UPDATE
                """).param("schedule", source.scheduleId()).param("id", source.id())
                .param("start", start).param("end", end).query(Long.class).list().isEmpty();
        if (ambiguous) throw conflict("ONCALL_HANDOFF_OVERLAP", "申请时段已有其他普通或覆盖班次，请先核对排班");
    }

    public CoverageView coverage(long id) {
        // One statement reads the accepted fact, replacement and separate revocation.
        // An accepted request alone never implies its replacement still provides coverage.
        return jdbc.sql("""
                SELECT h.*, CURRENT_TIMESTAMP(6) AS database_now,
                  r.handoff_id,r.actor_id,r.operation_key,r.handoff_version,r.replacement_version,
                  r.reason AS revocation_reason,r.revoked_at,
                  s.id AS coverage_id, s.user_id AS coverage_user, s.version AS coverage_version,
                  s.starts_at AS coverage_start, s.ends_at AS coverage_end,
                  s.cancelled_at AS coverage_cancelled, s.cancellation_reason AS coverage_cancel_reason
                FROM oncall_handoff h LEFT JOIN oncall_shift s ON s.id=h.replacement_shift_id
                LEFT JOIN oncall_handoff_revocation r ON r.handoff_id=h.id WHERE h.id=:id
                """).param("id", id).query((rs, row) -> new CoverageView(
                        rs.getObject("database_now", LocalDateTime.class), mapper.mapRow(rs, row),
                        rs.getObject("coverage_id", Long.class) == null ? null : new Replacement(
                                rs.getLong("coverage_id"), rs.getLong("coverage_user"), rs.getInt("coverage_version"),
                                rs.getObject("coverage_start", LocalDateTime.class), rs.getObject("coverage_end", LocalDateTime.class),
                                rs.getObject("coverage_cancelled", LocalDateTime.class), rs.getString("coverage_cancel_reason")),
                        rs.getObject("revoked_at", LocalDateTime.class) == null ? null : new Revocation(
                                rs.getLong("handoff_id"), rs.getLong("actor_id"), rs.getString("operation_key"),
                                rs.getInt("handoff_version"), rs.getInt("replacement_version"), rs.getString("revocation_reason"),
                                rs.getObject("revoked_at", LocalDateTime.class))))
                .optional().orElseThrow(() -> missing("接班请求不存在"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CoverageView revokeCoverage(long id, RevocationCommand command, long actorId, String ip) {
        String reason = text(command.reason());
        String key;
        try {
            key = UUID.fromString(command.operationKey()).toString();
            if (!key.equals(command.operationKey())) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException e) { throw invalid("撤销键须为规范 UUID"); }
        if (command.handoffVersion() < 0 || command.replacementVersion() < 0) throw invalid("请提供有效请求和覆盖版本");
        var initial = get(id, false);
        lockSchedule(initial.scheduleId());
        var row = get(id, true);
        // Actor lock serializes their operation keys across plans; check current DB role,
        // not merely a potentially old JWT management role.
        boolean manager = jdbc.sql("""
                SELECT id FROM sys_user WHERE id=:id AND status='ACTIVE'
                  AND role_code IN ('ADMIN','OPS_MANAGER') FOR UPDATE
                """).param("id", actorId).query(Long.class).optional().isPresent();
        if (!manager) throw forbidden("仅活跃管理员或运维经理可撤销接班覆盖");
        var previous = jdbc.sql("SELECT * FROM oncall_handoff_revocation WHERE actor_id=:actor AND operation_key=:key")
                .param("actor", actorId).param("key", key).query(revocationMapper).optional();
        if (previous.isPresent()) {
            var recorded = previous.get();
            if (recorded.handoffId() != id || recorded.handoffVersion() != command.handoffVersion()
                    || recorded.replacementVersion() != command.replacementVersion() || !recorded.reason().equals(reason)) {
                throw conflict("ONCALL_HANDOFF_REVOCATION_KEY_REUSED", "撤销键已用于不同内容");
            }
            return coverage(id); // Safe acknowledgement even after the shift has ended.
        }
        members.requireManager(row.scheduleId(), actorId);
        if (!row.status().equals("ACCEPTED") || row.replacementShiftId() == null || row.version() != command.handoffVersion()) {
            throw conflict("ONCALL_HANDOFF_VERSION_CONFLICT", "须核对已接受请求及捕获的版本");
        }
        var replacement = source(row.replacementShiftId());
        if (replacement.scheduleId() != row.scheduleId() || replacement.userId() != row.targetUserId() || !replacement.override()) {
            throw conflict("ONCALL_HANDOFF_SOURCE_CHANGED", "覆盖班次与接班请求不一致");
        }
        if (replacement.cancelledAt() != null || replacement.version() != command.replacementVersion()) {
            throw conflict("ONCALL_SHIFT_VERSION_CONFLICT", "覆盖已变化或已取消，请重新核对");
        }
        if (!replacement.endsAt().isAfter(now())) throw conflict("ONCALL_HANDOFF_EXPIRED", "覆盖时段已结束，不能撤销历史责任");
        roster.cancel(replacement.id(), command.replacementVersion(), reason, actorId, ip);
        jdbc.sql("""
                INSERT INTO oncall_handoff_revocation(handoff_id,actor_id,operation_key,handoff_version,
                  replacement_version,reason,revoked_at) VALUES (:id,:actor,:key,:requestVersion,:shiftVersion,:reason,:at)
                """).param("id", id).param("actor", actorId).param("key", key)
                .param("requestVersion", command.handoffVersion()).param("shiftVersion", command.replacementVersion())
                .param("reason", reason).param("at", now().truncatedTo(ChronoUnit.SECONDS)).update();
        audit.recordAs(actorId, ip, "ONCALL_HANDOFF_COVERAGE_REVOKED", "ONCALL_HANDOFF", id,
                "覆盖班次 #" + replacement.id() + "；" + reason);
        return coverage(id);
    }

    private void lockSchedule(long id) {
        jdbc.sql("SELECT active FROM oncall_schedule WHERE id=:id FOR UPDATE").param("id", id)
                .query(Boolean.class).optional().orElseThrow(() -> missing("计划不存在"));
    }

    private void requireActiveSchedule(long id) {
        if (!jdbc.sql("SELECT active FROM oncall_schedule WHERE id=:id").param("id", id).query(Boolean.class).single()) {
            throw conflict("ONCALL_SCHEDULE_INACTIVE", "计划已停用");
        }
    }

    private void lockUsers(long first, long second) {
        lockUser(Math.min(first, second));
        if (first != second) lockUser(Math.max(first, second));
    }

    private void lockUser(long id) {
        boolean eligible = jdbc.sql("""
                SELECT id FROM sys_user WHERE id=:id AND status='ACTIVE'
                  AND role_code IN ('ADMIN','OPS_MANAGER','ON_CALL') FOR UPDATE
                """).param("id", id).query(Long.class).optional().isPresent();
        if (!eligible) throw forbidden("用户须活跃且具有运维职责");
    }

    private Source source(long id) {
        return jdbc.sql("SELECT * FROM oncall_shift WHERE id=:id").param("id", id)
                .query((rs, row) -> new Source(rs.getLong("id"), rs.getLong("schedule_id"), rs.getLong("user_id"),
                        rs.getInt("version"), rs.getBoolean("override_flag"), rs.getObject("starts_at", LocalDateTime.class),
                        rs.getObject("ends_at", LocalDateTime.class), rs.getObject("cancelled_at", LocalDateTime.class)))
                .optional().orElseThrow(() -> missing("原班次不存在"));
    }

    private View get(long id, boolean lock) {
        return jdbc.sql("SELECT * FROM oncall_handoff WHERE id=:id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(mapper).optional().orElseThrow(() -> missing("接班请求不存在"));
    }

    private LocalDateTime now() {
        // MySQL and our H2 MODE=MySQL evaluate time per statement, not per transaction.
        // Read after all lock waits; do not use the time captured when the request arrived.
        return jdbc.sql("SELECT CURRENT_TIMESTAMP(6)").query((rs, row) -> rs.getObject(1, LocalDateTime.class)).single();
    }

    private static final RowMapper<View> mapper = (rs, row) -> new View(rs.getLong("id"), rs.getLong("schedule_id"),
            rs.getLong("source_shift_id"), rs.getInt("source_version"), rs.getLong("requester_id"),
            rs.getLong("target_user_id"), rs.getString("request_key"), rs.getObject("starts_at", LocalDateTime.class),
            rs.getObject("ends_at", LocalDateTime.class), rs.getString("reason"), rs.getString("status"),
            rs.getInt("version"), rs.getObject("created_at", LocalDateTime.class), rs.getObject("decided_at", LocalDateTime.class),
            rs.getObject("decided_by", Long.class), rs.getString("decision_reason"), rs.getObject("replacement_shift_id", Long.class));

    private static String text(String value) {
        if (value == null || value.isBlank() || value.length() > 500) throw invalid("说明须为 1–500 字");
        return value.strip();
    }
    private static final RowMapper<Revocation> revocationMapper = (rs, row) -> new Revocation(
            rs.getLong("handoff_id"), rs.getLong("actor_id"), rs.getString("operation_key"),
            rs.getInt("handoff_version"), rs.getInt("replacement_version"), rs.getString("reason"),
            rs.getObject("revoked_at", LocalDateTime.class));
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "ONCALL_HANDOFF_INVALID", message); }
    private static ApiException forbidden(String message) { return new ApiException(HttpStatus.FORBIDDEN, "ONCALL_HANDOFF_FORBIDDEN", message); }
    private static ApiException missing(String message) { return new ApiException(HttpStatus.NOT_FOUND, "ONCALL_HANDOFF_NOT_FOUND", message); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }

    private record Source(long id, long scheduleId, long userId, int version, boolean override,
                          LocalDateTime startsAt, LocalDateTime endsAt, LocalDateTime cancelledAt) {}
    public record Command(long sourceShiftId, int sourceVersion, long targetUserId, String requestKey,
                          LocalDateTime startsAt, LocalDateTime endsAt, String reason) {}
    public record Decision(int version, String status, String reason) {}
    public record View(long id, long scheduleId, long sourceShiftId, int sourceVersion, long requesterId,
                       long targetUserId, String requestKey, LocalDateTime startsAt, LocalDateTime endsAt,
                       String reason, String status, int version, LocalDateTime createdAt, LocalDateTime decidedAt,
                       Long decidedBy, String decisionReason, Long replacementShiftId) {}
    public record ListView(LocalDateTime databaseNow, List<View> requests, boolean truncated) {}
    public record RevocationCommand(int handoffVersion, int replacementVersion, String operationKey, String reason) {}
    public record Replacement(long id, long userId, int version, LocalDateTime startsAt, LocalDateTime endsAt,
                              LocalDateTime cancelledAt, String cancellationReason) {}
    public record Revocation(long handoffId, long actorId, String operationKey, int handoffVersion,
                             int replacementVersion, String reason, LocalDateTime revokedAt) {}
    public record CoverageView(LocalDateTime databaseNow, View request, Replacement replacement, Revocation revocation) {}
}
