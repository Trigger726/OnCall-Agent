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

/** A participant-confirmed exchange of two complete future ordinary shifts, not two independent handoffs. */
@Service
public class OnCallSwapService {
    private final JdbcClient jdbc;
    private final OnCallRosterService roster;
    private final AuditService audit;
    private final OnCallSwapNotifications notifications;
    private final OnCallPlanMembershipService members;

    public OnCallSwapService(JdbcClient jdbc, OnCallRosterService roster, AuditService audit, OnCallSwapNotifications notifications, OnCallPlanMembershipService members) {
        this.jdbc = jdbc; this.roster = roster; this.audit = audit; this.notifications = notifications;
        this.members = members;
    }

    public ListView list(Long scheduleId, Long participantId, String status) {
        if (status != null && !List.of("PENDING", "ACCEPTED", "REJECTED", "WITHDRAWN").contains(status)) throw invalid("无效换班状态");
        var rows = jdbc.sql("""
                SELECT * FROM oncall_shift_swap
                WHERE (:schedule IS NULL OR first_schedule_id=:schedule OR second_schedule_id=:schedule)
                  AND (:participant IS NULL OR requester_id=:participant OR target_user_id=:participant)
                  AND (:status IS NULL OR status=:status) ORDER BY id DESC LIMIT 201
                """).param("schedule", scheduleId).param("participant", participantId).param("status", status).query(mapper).list();
        return new ListView(now(), rows.stream().limit(200).toList(), rows.size() > 200);
    }

    public View get(long id) { return get(id, false); }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View request(Command command, long actorId, String ip) {
        String reason = text(command.reason()), key;
        try { key = UUID.fromString(command.requestKey()).toString(); if (!key.equals(command.requestKey())) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException | NullPointerException error) { throw invalid("请求键须为规范UUID"); }
        if (command.firstShiftId() == command.secondShiftId() || command.firstVersion() < 0 || command.secondVersion() < 0) throw invalid("请选择不同班次并提供有效版本");
        Source first = source(command.firstShiftId()), second = source(command.secondShiftId());
        lockSchedules(first.scheduleId(), second.scheduleId());
        first = source(first.id()); second = source(second.id());
        lockUsers(actorId, second.userId());
        var existing = jdbc.sql("SELECT * FROM oncall_shift_swap WHERE requester_id=:actor AND request_key=:key")
                .param("actor", actorId).param("key", key).query(mapper).optional();
        if (existing.isPresent()) {
            View prior = existing.get();
            if (prior.firstShiftId() != command.firstShiftId() || prior.secondShiftId() != command.secondShiftId()
                    || prior.firstVersion() != command.firstVersion() || prior.secondVersion() != command.secondVersion()
                    || !prior.reason().equals(reason)) throw conflict("ONCALL_SWAP_KEY_REUSED", "请求键已用于不同内容");
            return prior; // Acknowledges history; never creates new overrides after cancellation or expiry.
        }
        if (first.userId() != actorId) throw forbidden("只能申请交换自己的班次");
        if (first.userId() == second.userId()) throw invalid("双方须为不同负责人");
        validate(first, command.firstVersion()); validate(second, command.secondVersion());
        requireBothPlans(first.scheduleId(), second.scheduleId(), actorId, second.userId());
        var holder = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO oncall_shift_swap(requester_id,target_user_id,request_key,
                  first_schedule_id,first_shift_id,first_version,first_starts_at,first_ends_at,
                  second_schedule_id,second_shift_id,second_version,second_starts_at,second_ends_at,reason)
                VALUES (:actor,:target,:key,:fs,:first,:fv,:start1,:end1,:ss,:second,:sv,:start2,:end2,:reason)
                """).param("actor", actorId).param("target", second.userId()).param("key", key)
                .param("fs", first.scheduleId()).param("first", first.id()).param("fv", first.version())
                .param("start1", first.startsAt()).param("end1", first.endsAt())
                .param("ss", second.scheduleId()).param("second", second.id()).param("sv", second.version())
                .param("start2", second.startsAt()).param("end2", second.endsAt()).param("reason", reason).update(holder, "id");
        long id = holder.getKey().longValue();
        audit.recordAs(actorId, ip, "ONCALL_SWAP_REQUESTED", "ONCALL_SWAP", id, reason);
        View created = get(id);
        notifications.enqueue(created);
        return created;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public View decide(long id, Decision decision, long actorId, String ip) {
        String reason = text(decision.reason());
        if (decision.version() < 0 || decision.status() == null || !List.of("ACCEPTED", "REJECTED", "WITHDRAWN").contains(decision.status())) throw invalid("请提供有效决定与版本");
        View initial = get(id);
        lockSchedules(initial.firstScheduleId(), initial.secondScheduleId());
        View row = get(id, true);
        long participant = decision.status().equals("WITHDRAWN") ? row.requesterId() : row.targetUserId();
        if (actorId != participant) throw forbidden("仅申请人可撤回，仅指定对方可接受或拒绝");
        if (decision.status().equals("ACCEPTED") && row.status().equals("PENDING")) lockUsers(row.requesterId(), row.targetUserId());
        else lockUser(actorId);
        if (row.status().equals(decision.status()) && row.version() == decision.version() + 1 && reason.equals(row.decisionReason())) return row;
        if (!row.status().equals("PENDING") || row.version() != decision.version()) throw conflict("ONCALL_SWAP_VERSION_CONFLICT", "换班请求已变化，请刷新核对");
        Long firstReplacement = null, secondReplacement = null;
        if (decision.status().equals("ACCEPTED")) {
            requireBothPlans(row.firstScheduleId(), row.secondScheduleId(), row.requesterId(), row.targetUserId());
            Source first = source(row.firstShiftId()), second = source(row.secondShiftId());
            requireSnapshot(first, row.firstScheduleId(), row.requesterId(), row.firstStartsAt(), row.firstEndsAt());
            requireSnapshot(second, row.secondScheduleId(), row.targetUserId(), row.secondStartsAt(), row.secondEndsAt());
            validate(first, row.firstVersion()); validate(second, row.secondVersion());
            firstReplacement = roster.create(new OnCallRosterService.ShiftCommand(first.scheduleId(), row.targetUserId(),
                    first.startsAt(), first.endsAt(), true, "双向换班 #" + id + "，第一段"), actorId, ip).id();
            secondReplacement = roster.create(new OnCallRosterService.ShiftCommand(second.scheduleId(), row.requesterId(),
                    second.startsAt(), second.endsAt(), true, "双向换班 #" + id + "，第二段"), actorId, ip).id();
        }
        jdbc.sql("""
                UPDATE oncall_shift_swap SET status=:status,version=version+1,decided_at=:at,decided_by=:actor,
                  decision_reason=:reason,first_replacement_shift_id=:first,second_replacement_shift_id=:second WHERE id=:id
                """).param("id", id).param("status", decision.status()).param("actor", actorId).param("reason", reason)
                .param("at", now().truncatedTo(ChronoUnit.SECONDS)).param("first", firstReplacement).param("second", secondReplacement).update();
        audit.recordAs(actorId, ip, "ONCALL_SWAP_" + decision.status(), "ONCALL_SWAP", id, reason);
        View decided = get(id);
        notifications.enqueue(decided);
        return decided;
    }

    /** A single SQL statement separates immutable acceptance, both current replacements and revocation. */
    public CoverageView coverage(long id) {
        return jdbc.sql("""
                SELECT s.*, CURRENT_TIMESTAMP(6) AS database_now,
                  a.id AS first_coverage_id,a.schedule_id AS first_coverage_schedule,a.user_id AS first_coverage_user,
                  a.version AS first_coverage_version,a.starts_at AS first_coverage_start,a.ends_at AS first_coverage_end,
                  a.cancelled_at AS first_coverage_cancelled,a.cancellation_reason AS first_coverage_reason,
                  b.id AS second_coverage_id,b.schedule_id AS second_coverage_schedule,b.user_id AS second_coverage_user,
                  b.version AS second_coverage_version,b.starts_at AS second_coverage_start,b.ends_at AS second_coverage_end,
                  b.cancelled_at AS second_coverage_cancelled,b.cancellation_reason AS second_coverage_reason,
                  r.swap_id AS revoked_swap,r.actor_id,r.operation_key,r.swap_version,r.first_replacement_version,
                  r.second_replacement_version,r.reason AS revocation_reason,r.revoked_at
                FROM oncall_shift_swap s LEFT JOIN oncall_shift a ON a.id=s.first_replacement_shift_id
                LEFT JOIN oncall_shift b ON b.id=s.second_replacement_shift_id
                LEFT JOIN oncall_swap_revocation r ON r.swap_id=s.id WHERE s.id=:id
                """).param("id",id).query((rs,n)->new CoverageView(rs.getObject("database_now",LocalDateTime.class),mapper.mapRow(rs,n),
                        replacement(rs,"first"),replacement(rs,"second"),rs.getObject("revoked_swap",Long.class)==null?null:new Revocation(
                        rs.getLong("revoked_swap"),rs.getLong("actor_id"),rs.getString("operation_key"),rs.getInt("swap_version"),
                        rs.getInt("first_replacement_version"),rs.getInt("second_replacement_version"),rs.getString("revocation_reason"),rs.getObject("revoked_at",LocalDateTime.class))))
                .optional().orElseThrow(()->missing("换班请求不存在"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CoverageView revokeCoverage(long id, RevocationCommand command, long actorId, String ip) {
        String reason = text(command.reason()), key;
        try {
            key = UUID.fromString(command.operationKey()).toString();
            if (!key.equals(command.operationKey())) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | NullPointerException error) { throw invalid("撤销键须为规范UUID"); }
        if (command.swapVersion() < 0 || command.firstReplacementVersion() < 0 || command.secondReplacementVersion() < 0)
            throw invalid("请显式提供原换班及两条覆盖版本");
        var initial = get(id);
        lockSchedules(initial.firstScheduleId(), initial.secondScheduleId());
        var row = get(id, true);
        boolean manager = jdbc.sql("SELECT id FROM sys_user WHERE id=:id AND status='ACTIVE' AND role_code IN ('ADMIN','OPS_MANAGER') FOR UPDATE")
                .param("id", actorId).query(Long.class).optional().isPresent();
        if (!manager) throw forbidden("仅当前活跃管理员或运维经理可成对撤销覆盖");
        var previous = jdbc.sql("SELECT * FROM oncall_swap_revocation WHERE actor_id=:actor AND operation_key=:key")
                .param("actor", actorId).param("key", key).query(revocationMapper).optional();
        if (previous.isPresent()) {
            var recorded = previous.get();
            if (recorded.swapId() != id || recorded.swapVersion() != command.swapVersion()
                    || recorded.firstReplacementVersion() != command.firstReplacementVersion()
                    || recorded.secondReplacementVersion() != command.secondReplacementVersion() || !recorded.reason().equals(reason))
                throw conflict("ONCALL_SWAP_REVOCATION_KEY_REUSED", "撤销键已用于不同换班、版本或说明");
            return coverage(id); // Acknowledges the original command; never revives either replacement.
        }
        members.requireManager(row.firstScheduleId(), actorId);
        members.requireManager(row.secondScheduleId(), actorId);
        if (!row.status().equals("ACCEPTED") || row.version() != command.swapVersion()
                || row.firstReplacementShiftId() == null || row.secondReplacementShiftId() == null)
            throw conflict("ONCALL_SWAP_VERSION_CONFLICT", "须核对原已接受换班及捕获版本");
        if (jdbc.sql("SELECT swap_id FROM oncall_swap_revocation WHERE swap_id=:id").param("id", id).query(Long.class).optional().isPresent())
            throw conflict("ONCALL_SWAP_COVERAGE_ALREADY_REVOKED", "两段已成对撤销，只能核对原回执");
        var first = source(row.firstReplacementShiftId());
        var second = source(row.secondReplacementShiftId());
        requireSnapshot(first, row.firstScheduleId(), row.targetUserId(), row.firstStartsAt(), row.firstEndsAt());
        requireSnapshot(second, row.secondScheduleId(), row.requesterId(), row.secondStartsAt(), row.secondEndsAt());
        if (!first.override() || !second.override()) throw conflict("ONCALL_SWAP_SOURCE_CHANGED", "两段须为原换班生成的覆盖");
        if (first.cancelledAt() != null || second.cancelledAt() != null
                || first.version() != command.firstReplacementVersion() || second.version() != command.secondReplacementVersion()
                || first.version() == Integer.MAX_VALUE || second.version() == Integer.MAX_VALUE)
            throw conflict("ONCALL_SHIFT_VERSION_CONFLICT", "任一覆盖已变化或已取消，不能做半撤销");
        requireRemaining(first, second);
        roster.cancel(first.id(), command.firstReplacementVersion(), reason, actorId, ip);
        roster.cancel(second.id(), command.secondReplacementVersion(), reason, actorId, ip);
        jdbc.sql("""
                INSERT INTO oncall_swap_revocation(swap_id,actor_id,operation_key,swap_version,first_replacement_version,second_replacement_version,reason,revoked_at)
                VALUES (:id,:actor,:key,:swap,:first,:second,:reason,:at)
                """).param("id",id).param("actor",actorId).param("key",key).param("swap",command.swapVersion())
                .param("first",command.firstReplacementVersion()).param("second",command.secondReplacementVersion()).param("reason",reason)
                .param("at",now().truncatedTo(ChronoUnit.MICROS)).update();
        audit.recordAs(actorId,ip,"ONCALL_SWAP_COVERAGE_REVOKED","ONCALL_SWAP",id,"两条覆盖 #"+first.id()+" / #"+second.id()+"；"+reason);
        requireRemaining(first, second); // Recheck after audit work; an ended pair must roll back together.
        return coverage(id);
    }

    private void requireRemaining(Source first, Source second) {
        var current = now();
        if (!first.endsAt().isAfter(current) || !second.endsAt().isAfter(current))
            throw conflict("ONCALL_SWAP_COVERAGE_EXPIRED", "任一覆盖已结束，不能撤销历史责任");
    }
    private static Replacement replacement(java.sql.ResultSet rs,String prefix)throws java.sql.SQLException {
        String p=prefix+"_coverage_";return rs.getObject(p+"id",Long.class)==null?null:new Replacement(rs.getLong(p+"id"),rs.getLong(p+"schedule"),rs.getLong(p+"user"),
                rs.getInt(p+"version"),rs.getObject(p+"start",LocalDateTime.class),rs.getObject(p+"end",LocalDateTime.class),rs.getObject(p+"cancelled",LocalDateTime.class),rs.getString(p+"reason"));
    }
    private static final RowMapper<Revocation> revocationMapper=(rs,n)->new Revocation(rs.getLong("swap_id"),rs.getLong("actor_id"),rs.getString("operation_key"),
            rs.getInt("swap_version"),rs.getInt("first_replacement_version"),rs.getInt("second_replacement_version"),rs.getString("reason"),rs.getObject("revoked_at",LocalDateTime.class));

    private void validate(Source source, int version) {
        boolean active = jdbc.sql("SELECT active FROM oncall_schedule WHERE id=:id").param("id", source.scheduleId()).query(Boolean.class).single();
        if (!active) throw conflict("ONCALL_SCHEDULE_INACTIVE", "计划已停用");
        if (source.override() || source.cancelledAt() != null || source.version() != version) throw conflict("ONCALL_SWAP_SOURCE_CHANGED", "原班次已变化或为临时覆盖");
        if (!source.startsAt().isAfter(now())) throw conflict("ONCALL_SWAP_STARTED", "仅可互换尚未开始的完整普通班次");
        boolean overlap = !jdbc.sql("""
                SELECT id FROM oncall_shift WHERE schedule_id=:schedule AND id<>:id AND cancelled_at IS NULL
                  AND starts_at<:end AND ends_at>:start FOR UPDATE
                """).param("schedule", source.scheduleId()).param("id", source.id()).param("start", source.startsAt())
                .param("end", source.endsAt()).query(Long.class).list().isEmpty();
        if (overlap) throw conflict("ONCALL_SWAP_OVERLAP", "原时段已有其他普通或覆盖班次，请先核对排班");
    }

    private void requireSnapshot(Source source, long schedule, long user, LocalDateTime start, LocalDateTime end) {
        if (source.scheduleId() != schedule || source.userId() != user || !source.startsAt().equals(start) || !source.endsAt().equals(end)) throw conflict("ONCALL_SWAP_SOURCE_CHANGED", "原负责人、计划或时段已变化");
    }

    private void lockSchedules(long first, long second) {
        lockSchedule(Math.min(first, second)); if (first != second) lockSchedule(Math.max(first, second));
    }
    private void requireBothPlans(long first, long second, long requester, long target) {
        members.requireResponder(first, requester);
        members.requireResponder(first, target);
        if (first != second) {
            members.requireResponder(second, requester);
            members.requireResponder(second, target);
        }
    }
    private void lockSchedule(long id) {
        jdbc.sql("SELECT active FROM oncall_schedule WHERE id=:id FOR UPDATE").param("id", id).query(Boolean.class)
                .optional().orElseThrow(() -> missing("计划不存在"));
    }
    private void lockUsers(long first, long second) { lockUser(Math.min(first, second)); if (first != second) lockUser(Math.max(first, second)); }
    private void lockUser(long id) {
        boolean eligible = jdbc.sql("SELECT id FROM sys_user WHERE id=:id AND status='ACTIVE' AND role_code IN ('ADMIN','OPS_MANAGER','ON_CALL') FOR UPDATE")
                .param("id", id).query(Long.class).optional().isPresent();
        if (!eligible) throw forbidden("双方须活跃且具有运维职责");
    }
    private Source source(long id) {
        return jdbc.sql("SELECT * FROM oncall_shift WHERE id=:id").param("id", id).query((rs, n) -> new Source(rs.getLong("id"),
                rs.getLong("schedule_id"), rs.getLong("user_id"), rs.getInt("version"), rs.getBoolean("override_flag"),
                rs.getObject("starts_at", LocalDateTime.class), rs.getObject("ends_at", LocalDateTime.class), rs.getObject("cancelled_at", LocalDateTime.class)))
                .optional().orElseThrow(() -> missing("班次不存在"));
    }
    private View get(long id, boolean lock) {
        return jdbc.sql("SELECT * FROM oncall_shift_swap WHERE id=:id" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(mapper).optional().orElseThrow(() -> missing("换班请求不存在"));
    }
    private LocalDateTime now() { return jdbc.sql("SELECT CURRENT_TIMESTAMP(6)").query((rs, n) -> rs.getObject(1, LocalDateTime.class)).single(); }
    private static String text(String value) { if (value == null || value.isBlank() || value.length() > 500) throw invalid("说明须为1–500字"); return value.strip(); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "ONCALL_SWAP_INVALID", message); }
    private static ApiException forbidden(String message) { return new ApiException(HttpStatus.FORBIDDEN, "ONCALL_SWAP_FORBIDDEN", message); }
    private static ApiException missing(String message) { return new ApiException(HttpStatus.NOT_FOUND, "ONCALL_SWAP_NOT_FOUND", message); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private static final RowMapper<View> mapper = (rs, n) -> new View(rs.getLong("id"), rs.getLong("requester_id"), rs.getLong("target_user_id"),
            rs.getString("request_key"), rs.getLong("first_schedule_id"), rs.getLong("first_shift_id"), rs.getInt("first_version"),
            rs.getObject("first_starts_at", LocalDateTime.class), rs.getObject("first_ends_at", LocalDateTime.class),
            rs.getLong("second_schedule_id"), rs.getLong("second_shift_id"), rs.getInt("second_version"),
            rs.getObject("second_starts_at", LocalDateTime.class), rs.getObject("second_ends_at", LocalDateTime.class),
            rs.getString("reason"), rs.getString("status"), rs.getInt("version"), rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("decided_at", LocalDateTime.class), rs.getObject("decided_by", Long.class), rs.getString("decision_reason"),
            rs.getObject("first_replacement_shift_id", Long.class), rs.getObject("second_replacement_shift_id", Long.class));

    private record Source(long id, long scheduleId, long userId, int version, boolean override, LocalDateTime startsAt, LocalDateTime endsAt, LocalDateTime cancelledAt) {}
    public record Command(long firstShiftId, int firstVersion, long secondShiftId, int secondVersion, String requestKey, String reason) {}
    public record Decision(int version, String status, String reason) {}
    public record RevocationCommand(int swapVersion,int firstReplacementVersion,int secondReplacementVersion,String operationKey,String reason){}
    public record Replacement(long id,long scheduleId,long userId,int version,LocalDateTime startsAt,LocalDateTime endsAt,LocalDateTime cancelledAt,String cancellationReason){}
    public record Revocation(long swapId,long actorId,String operationKey,int swapVersion,int firstReplacementVersion,int secondReplacementVersion,String reason,LocalDateTime revokedAt){}
    public record CoverageView(LocalDateTime databaseNow,View accepted,Replacement firstReplacement,Replacement secondReplacement,Revocation revocation){}
    public record View(long id, long requesterId, long targetUserId, String requestKey,
                       long firstScheduleId, long firstShiftId, int firstVersion, LocalDateTime firstStartsAt, LocalDateTime firstEndsAt,
                       long secondScheduleId, long secondShiftId, int secondVersion, LocalDateTime secondStartsAt, LocalDateTime secondEndsAt,
                       String reason, String status, int version, LocalDateTime createdAt, LocalDateTime decidedAt, Long decidedBy,
                       String decisionReason, Long firstReplacementShiftId, Long secondReplacementShiftId) {}
    public record ListView(LocalDateTime databaseNow, List<View> requests, boolean truncated) {}
}
