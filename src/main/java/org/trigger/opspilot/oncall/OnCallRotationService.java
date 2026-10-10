package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class OnCallRotationService {
    private static final Logger log = LoggerFactory.getLogger(OnCallRotationService.class);
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final OnCallRosterService roster;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final OnCallPlanMembershipService planMembers;

    public OnCallRotationService(JdbcClient jdbc, ObjectMapper json, OnCallRosterService roster,
                                 AuditService audit, PlatformTransactionManager transactionManager, OnCallPlanMembershipService planMembers) {
        this.jdbc = jdbc;
        this.json = json;
        this.roster = roster;
        this.audit = audit;
        this.planMembers = planMembers;
        this.transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RotationView create(Command command, Long actor, String ip) {
        LocalDateTime now = now();
        if (command == null || command.name() == null || command.name().isBlank() || command.name().length() > 128
                || command.anchorAt() == null || command.anchorAt().getSecond() != 0 || command.anchorAt().getNano() != 0
                || command.anchorAt().isBefore(now.minusDays(31)) || command.anchorAt().isAfter(now.plusDays(31))
                || command.shiftMinutes() < 60 || command.shiftMinutes() > 10080
                || command.members() == null || command.members().isEmpty() || command.members().size() > 20
                || command.members().stream().anyMatch(id -> id == null || id < 1)
                || command.members().stream().distinct().count() != command.members().size()) {
            throw invalid("名称 1–128 字；锚点须为整分钟且距现在不超过 31 天；单班 60–10080 分钟；1–20 名不重复成员");
        }
        if (!lockSchedule(command.scheduleId())) throw conflict("ONCALL_SCHEDULE_INACTIVE", "计划已停用");
        lockParticipants(command.members(), actor);
        requireManagement(command.scheduleId(), actor);
        ensureNoActiveRotation(command.scheduleId(), 0);
        List<Long> eligible = eligibleMembers(command.scheduleId(), command.members());
        if (eligible.size() != command.members().size()) throw invalid("成员必须全部活跃且具有运维职责");
        String members;
        try { members = json.writeValueAsString(command.members()); }
        catch (Exception error) { throw new IllegalStateException("Cannot encode rotation members", error); }
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO oncall_rotation(schedule_id, name, anchor_at, shift_minutes, member_ids_json, created_by)
                        VALUES (:schedule, :name, :anchor, :minutes, :members, :actor)
                        """).param("schedule", command.scheduleId()).param("name", command.name().strip())
                .param("anchor", command.anchorAt()).param("minutes", command.shiftMinutes())
                .param("members", members).param("actor", actor).update(key, "id");
        long id = key.getKey().longValue();
        audit.recordAs(actor, ip, "ONCALL_ROTATION_CREATED", "ONCALL_ROTATION", id,
                "计划 " + command.scheduleId() + "，每班 " + command.shiftMinutes() + " 分钟，成员顺序 " + members);
        generate(id, now, actor, ip);
        return get(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RotationView state(long id, int version, boolean active, String reason, Long actor, String ip) {
        if (reason == null || reason.isBlank() || reason.length() > 500) throw invalid("变更原因须为 1–500 字");
        RotationView initial = get(id);
        boolean scheduleActive = lockSchedule(initial.scheduleId());
        lockParticipants(initial.members(), actor);
        requireManagement(initial.scheduleId(), actor);
        if (active) {
            if (!scheduleActive) throw conflict("ONCALL_SCHEDULE_INACTIVE", "计划已停用");
            ensureNoActiveRotation(initial.scheduleId(), id);
        }
        int updated = jdbc.sql("""
                        UPDATE oncall_rotation SET active = :active, version = version + 1, state_reason = :reason
                        WHERE id = :id AND version = :version AND active <> :active
                        """).param("active", active).param("reason", reason.strip())
                .param("id", id).param("version", version).update();
        if (updated != 1) throw conflict("ONCALL_ROTATION_VERSION_CONFLICT", "轮转已变化，请刷新核对");
        audit.recordAs(actor, ip, active ? "ONCALL_ROTATION_RESUMED" : "ONCALL_ROTATION_PAUSED",
                "ONCALL_ROTATION", id, reason.strip() + "；既有班次和取消历史保持不变");
        if (active) generate(id, now(), actor, ip);
        return get(id);
    }

    public RotationList list(Long scheduleId) {
        var rows = jdbc.sql("""
                        SELECT * FROM oncall_rotation WHERE (:schedule IS NULL OR schedule_id = :schedule)
                        ORDER BY id DESC LIMIT 101
                        """).param("schedule", scheduleId).query(this::mapRotation).list();
        return new RotationList(rows.stream().limit(100).toList(), rows.size() > 100);
    }

    public RotationView get(long id) {
        return jdbc.sql("SELECT * FROM oncall_rotation WHERE id = :id").param("id", id)
                .query(this::mapRotation).optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "ONCALL_ROTATION_NOT_FOUND", "轮转不存在"));
    }

    public SlotWindow slots(long id, LocalDateTime from, LocalDateTime to) {
        var rotation = get(id);
        LocalDateTime now = now();
        LocalDateTime start = from == null ? now.minusDays(1) : from;
        LocalDateTime end = to == null ? now.plusDays(14) : to;
        if (!end.isAfter(start) || end.isAfter(start.plusDays(31))) throw invalid("窗口须为正数且不超过 31 天");
        var rows = jdbc.sql("""
                        SELECT slot.*, u.display_name, u.status AS user_status, u.role_code, shift.cancelled_at,
                          m.active AS member_active,m.can_respond AS member_respond
                        FROM oncall_rotation_slot slot JOIN sys_user u ON u.id = slot.user_id
                        LEFT JOIN oncall_shift shift ON shift.id = slot.shift_id
                        LEFT JOIN oncall_schedule_member m ON m.schedule_id=:schedule AND m.user_id=slot.user_id
                        WHERE slot.rotation_id = :id AND slot.starts_at < :end AND slot.ends_at > :start
                        ORDER BY slot.slot_index LIMIT 201
                        """).param("id", id).param("schedule", rotation.scheduleId()).param("start", start).param("end", end)
                .query((rs, row) -> new SlotView(rs.getLong("slot_index"), rs.getLong("user_id"),
                        rs.getString("display_name"), rs.getObject("starts_at", LocalDateTime.class),
                        rs.getObject("ends_at", LocalDateTime.class), rs.getString("status"), rs.getString("detail"),
                        rs.getObject("shift_id", Long.class), rs.getObject("cancelled_at", LocalDateTime.class),
                        "ACTIVE".equals(rs.getString("user_status"))
                                && rs.getBoolean("member_active") && rs.getBoolean("member_respond")
                                && List.of("ADMIN", "OPS_MANAGER", "ON_CALL").contains(rs.getString("role_code")))).list();
        return new SlotWindow(start, end, rows.stream().limit(200).toList(), rows.size() > 200);
    }

    public ScanResult scan(LocalDateTime requestedAt, Long actor, String ip) {
        LocalDateTime at = requestedAt == null ? now() : requestedAt;
        List<Long> ids = jdbc.sql("SELECT id FROM oncall_rotation WHERE active = TRUE ORDER BY last_scan_at, id LIMIT 100")
                .query(Long.class).list();
        int created = 0, blocked = 0;
        List<Long> failed = new ArrayList<>();
        for (long id : ids) {
            try {
                Counts counts = transactions.execute(tx -> generate(id, at, actor, ip));
                created += counts.created();
                blocked += counts.blocked();
            } catch (RuntimeException error) {
                failed.add(id);
                log.warn("On-call rotation {} generation failed", id, error);
                try {
                    jdbc.sql("UPDATE oncall_rotation SET last_scan_at = CURRENT_TIMESTAMP, last_warning = 'GENERATION_FAILED' WHERE id = :id")
                            .param("id", id).update();
                } catch (RuntimeException persistenceError) { log.warn("Cannot record rotation {} failure", id); }
            }
        }
        return new ScanResult(at, ids.size(), created, blocked, List.copyOf(failed));
    }

    private Counts generate(long id, LocalDateTime at, Long actor, String ip) {
        long schedule = get(id).scheduleId();
        boolean scheduleActive = lockSchedule(schedule);
        RotationView rotation = jdbc.sql("SELECT * FROM oncall_rotation WHERE id = :id FOR UPDATE")
                .param("id", id).query(this::mapRotation).single();
        if (!rotation.active()) return new Counts(0, 0);
        lockParticipants(rotation.members(), actor);
        if (actor != null) requireManagement(schedule, actor);
        if (!scheduleActive) {
            markScan(id, "SCHEDULE_INACTIVE");
            return new Counts(0, 0);
        }
        List<Long> eligible = eligibleMembers(schedule, rotation.members());
        long index = Math.max(0, Math.floorDiv(Duration.between(rotation.anchorAt(), at).getSeconds(),
                rotation.shiftMinutes() * 60L));
        LocalDateTime horizon = at.plusDays(14);
        int created = 0, blocked = 0;
        for (int attempt = 0; attempt < 512; attempt++, index++) {
            LocalDateTime start = rotation.anchorAt().plusMinutes(index * rotation.shiftMinutes());
            if (!start.isBefore(horizon)) break;
            LocalDateTime end = start.plusMinutes(rotation.shiftMinutes());
            String existing = jdbc.sql("SELECT status FROM oncall_rotation_slot WHERE rotation_id = :id AND slot_index = :slot FOR UPDATE")
                    .param("id", id).param("slot", index).query(String.class).optional().orElse(null);
            long user = rotation.members().get((int) (index % rotation.members().size()));
            // Generation is a historical fact; current eligibility is reported separately, never reassigned.
            if ("GENERATED".equals(existing)) {
                if (!eligible.contains(user) && jdbc.sql("""
                                SELECT COUNT(*) FROM oncall_shift WHERE rotation_id = :id
                                  AND rotation_slot = :slot AND cancelled_at IS NULL
                                """).param("id", id).param("slot", index).query(Long.class).single() > 0) blocked++;
                continue;
            }
            String status, detail;
            Long shift = null;
            if (!eligible.contains(user)) {
                status = "MEMBER_UNAVAILABLE";
                detail = "成员停用、不再具有运维职责或失去此计划响应权限；未跳过其轮次换人";
                blocked++;
            } else if (!jdbc.sql("""
                            SELECT id FROM oncall_shift WHERE schedule_id = :schedule AND cancelled_at IS NULL
                              AND override_flag = FALSE AND starts_at < :end AND ends_at > :start FOR UPDATE
                            """).param("schedule", schedule).param("start", start).param("end", end)
                    .query(Long.class).list().isEmpty()) {
                status = "BLOCKED";
                detail = "与既有普通班次重叠；保留手工/既有排班，不覆盖";
                blocked++;
            } else {
                shift = roster.create(new OnCallRosterService.ShiftCommand(schedule, user, start, end, false,
                        "轮转#" + id + " 时段#" + index + " · " + rotation.name()), actor, ip).id();
                jdbc.sql("UPDATE oncall_shift SET rotation_id = :id, rotation_slot = :slot WHERE id = :shift")
                        .param("id", id).param("slot", index).param("shift", shift).update();
                status = "GENERATED";
                detail = "已生成普通班次；临时覆盖仍优先，取消后不自动重建";
                created++;
            }
            if (existing == null) {
                jdbc.sql("""
                                INSERT INTO oncall_rotation_slot(rotation_id, slot_index, user_id, starts_at, ends_at, status, detail, shift_id)
                                VALUES (:id, :slot, :user, :start, :end, :status, :detail, :shift)
                                """).param("id", id).param("slot", index).param("user", user)
                        .param("start", start).param("end", end).param("status", status)
                        .param("detail", detail).param("shift", shift).update();
            } else {
                jdbc.sql("""
                                UPDATE oncall_rotation_slot SET status = :status, detail = :detail, shift_id = :shift,
                                  updated_at = CURRENT_TIMESTAMP WHERE rotation_id = :id AND slot_index = :slot
                                """).param("id", id).param("slot", index).param("status", status)
                        .param("detail", detail).param("shift", shift).update();
            }
        }
        markScan(id, blocked > 0 ? "BLOCKED_SLOTS:" + blocked : null);
        if (created > 0) audit.recordAs(actor, ip, "ONCALL_ROTATION_MATERIALIZED", "ONCALL_ROTATION", id,
                "生成 " + created + " 班，受阻 " + blocked + " 时段；提前 14 天续排");
        return new Counts(created, blocked);
    }

    private void markScan(long id, String warning) {
        jdbc.sql("UPDATE oncall_rotation SET last_scan_at = CURRENT_TIMESTAMP, last_warning = :warning WHERE id = :id")
                .param("id", id).param("warning", warning).update();
    }

    private void requireManagement(long schedule, Long actor) {
        if (actor == null) throw new ApiException(HttpStatus.FORBIDDEN, "ONCALL_PLAN_MANAGEMENT_FORBIDDEN", "须提供实际管理人");
        planMembers.requireManager(schedule, actor);
    }

    private void lockParticipants(List<Long> members, Long actor) {
        var ids = new ArrayList<>(members);
        if (actor != null) ids.add(actor);
        planMembers.lockAccounts(ids.stream().mapToLong(Long::longValue).toArray());
    }

    private List<Long> eligibleMembers(long schedule, List<Long> members) {
        return jdbc.sql("""
                        SELECT u.id FROM sys_user u JOIN oncall_schedule_member m ON m.user_id=u.id
                        WHERE u.id IN (:ids) AND u.status = 'ACTIVE' AND m.schedule_id=:schedule
                          AND m.active=TRUE AND m.can_respond=TRUE
                          AND u.role_code IN ('ADMIN','OPS_MANAGER','ON_CALL') ORDER BY u.id FOR UPDATE
                        """).param("ids", members).param("schedule", schedule).query(Long.class).list();
    }

    private void ensureNoActiveRotation(long schedule, long excludedId) {
        if (!jdbc.sql("SELECT id FROM oncall_rotation WHERE schedule_id = :schedule AND active = TRUE AND id <> :id FOR UPDATE")
                .param("schedule", schedule).param("id", excludedId).query(Long.class).list().isEmpty()) {
            throw conflict("ONCALL_ROTATION_EXISTS", "同一计划只能有一条正在续排的轮转");
        }
    }

    private boolean lockSchedule(long id) {
        return jdbc.sql("SELECT active FROM oncall_schedule WHERE id = :id FOR UPDATE").param("id", id)
                .query(Boolean.class).optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "ONCALL_SCHEDULE_NOT_FOUND", "计划不存在"));
    }

    private RotationView mapRotation(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        List<Long> members;
        try { members = json.readValue(rs.getString("member_ids_json"), new TypeReference<List<Long>>() {}); }
        catch (Exception error) { throw new IllegalStateException("Invalid persisted rotation members", error); }
        return new RotationView(rs.getLong("id"), rs.getLong("schedule_id"), rs.getString("name"),
                rs.getObject("anchor_at", LocalDateTime.class), rs.getInt("shift_minutes"), members,
                rs.getBoolean("active"), rs.getInt("version"), rs.getString("state_reason"),
                rs.getObject("last_scan_at", LocalDateTime.class), rs.getString("last_warning"));
    }

    private LocalDateTime now() { return jdbc.sql("SELECT CURRENT_TIMESTAMP")
            .query((rs, row) -> rs.getObject(1, LocalDateTime.class)).single(); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "ONCALL_ROTATION_INVALID", message); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }

    public record Command(long scheduleId, String name, LocalDateTime anchorAt, int shiftMinutes, List<Long> members) {}
    public record RotationView(long id, long scheduleId, String name, LocalDateTime anchorAt, int shiftMinutes,
                               List<Long> members, boolean active, int version, String stateReason,
                               LocalDateTime lastScanAt, String lastWarning) {}
    public record RotationList(List<RotationView> rotations, boolean truncated) {}
    public record SlotView(long slot, long userId, String userName, LocalDateTime startsAt, LocalDateTime endsAt,
                           String status, String detail, Long shiftId, LocalDateTime cancelledAt, boolean memberAvailable) {}
    public record SlotWindow(LocalDateTime from, LocalDateTime to, List<SlotView> slots, boolean truncated) {}
    public record ScanResult(LocalDateTime asOf, int rotations, int createdShifts, int blockedSlots, List<Long> failedRotations) {}
    private record Counts(int created, int blocked) {}
}
