package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class OnCallPlanMembershipService {
    private final JdbcClient jdbc;
    private final AuditService audit;

    public OnCallPlanMembershipService(JdbcClient jdbc, AuditService audit) { this.jdbc = jdbc; this.audit = audit; }

    public List<MemberView> list(long schedule) {
        requireSchedule(schedule, false);
        return jdbc.sql("""
                SELECT m.*,u.display_name,u.status,u.role_code FROM oncall_schedule_member m
                JOIN sys_user u ON u.id=m.user_id WHERE m.schedule_id=:schedule ORDER BY m.user_id
                """).param("schedule", schedule).query(memberMapper).list();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ChangeResult change(long schedule, Command command, long actor, String ip) {
        String key = key(command.operationKey()), reason = text(command.reason());
        if (command.userId() < 1 || command.expectedVersion() != null && command.expectedVersion() < 0
                || command.active() && !command.canRespond() && !command.canManage()) throw invalid("请提供有效用户、捕获版本与独立权限");
        requireSchedule(schedule, true);
        lockAccounts(actor, command.userId());
        requireGlobalManager(actor);
        var previous = jdbc.sql("SELECT * FROM oncall_schedule_member_operation WHERE actor_id=:actor AND operation_key=:key")
                .param("actor", actor).param("key", key).query(operationMapper).optional();
        if (previous.isPresent()) {
            var saved = previous.get();
            if (saved.scheduleId() != schedule || saved.userId() != command.userId()
                    || !Objects.equals(saved.expectedVersion(), command.expectedVersion()) || saved.active() != command.active()
                    || saved.canRespond() != command.canRespond() || saved.canManage() != command.canManage()
                    || !saved.reason().equals(reason)) throw conflict("ONCALL_MEMBER_KEY_REUSED", "成员操作键已用于不同计划、成员或内容");
            return new ChangeResult(member(schedule, command.userId()), saved); // Read-only original acknowledgement, even after own plan management was removed.
        }
        requireManager(schedule, actor);
        if (command.active()) {
            String role = jdbc.sql("SELECT role_code FROM sys_user WHERE id=:id AND status='ACTIVE'")
                    .param("id", command.userId()).query(String.class).optional().orElseThrow(() -> invalid("授权目标须为活跃用户"));
            if (!List.of("ADMIN", "OPS_MANAGER", "ON_CALL").contains(role)
                    || command.canManage() && !List.of("ADMIN", "OPS_MANAGER").contains(role)) throw invalid("响应目标须有运维职责，管理目标须为管理员或运维经理");
        }
        var old = jdbc.sql("SELECT m.*,u.display_name,u.status,u.role_code FROM oncall_schedule_member m JOIN sys_user u ON u.id=m.user_id WHERE m.schedule_id=:schedule AND m.user_id=:user FOR UPDATE")
                .param("schedule", schedule).param("user", command.userId()).query(memberMapper).optional();
        Integer oldVersion = old.map(MemberView::version).orElse(null);
        if (!Objects.equals(oldVersion, command.expectedVersion()) || Objects.equals(oldVersion, Integer.MAX_VALUE))
            throw conflict("ONCALL_MEMBER_VERSION_CONFLICT", "成员关系已变化，不自动更新捕获版本");
        var committed = now();
        int version = old.isEmpty() ? 0 : old.get().version() + 1;
        if (old.isEmpty()) {
            jdbc.sql("""
                    INSERT INTO oncall_schedule_member(schedule_id,user_id,active,can_respond,can_manage,version,origin,created_at,updated_at)
                    VALUES (:schedule,:user,:active,:respond,:manage,0,'EXPLICIT',:at,:at)
                    """).param("schedule", schedule).param("user", command.userId()).param("active", command.active())
                    .param("respond", command.canRespond()).param("manage", command.canManage()).param("at", committed).update();
        } else {
            jdbc.sql("""
                    UPDATE oncall_schedule_member SET active=:active,can_respond=:respond,can_manage=:manage,version=:version,updated_at=:at
                    WHERE schedule_id=:schedule AND user_id=:user
                    """).param("schedule", schedule).param("user", command.userId()).param("active", command.active())
                    .param("respond", command.canRespond()).param("manage", command.canManage()).param("version", version).param("at", committed).update();
        }
        jdbc.sql("""
                INSERT INTO oncall_schedule_member_operation(schedule_id,user_id,actor_id,operation_key,expected_version,result_version,
                  active,can_respond,can_manage,reason,committed_at)
                VALUES (:schedule,:user,:actor,:key,:expected,:version,:active,:respond,:manage,:reason,:at)
                """).param("schedule", schedule).param("user", command.userId()).param("actor", actor).param("key", key)
                .param("expected", command.expectedVersion()).param("version", version).param("active", command.active())
                .param("respond", command.canRespond()).param("manage", command.canManage()).param("reason", reason).param("at", committed).update();
        audit.recordAs(actor, ip, "ONCALL_MEMBER_CHANGED", "ONCALL_SCHEDULE", schedule,
                "成员 " + command.userId() + "，版本 " + oldVersion + " -> " + version
                        + "，old" + old.map(m -> permissions(m.active(), m.canRespond(), m.canManage())).orElse("[absent]")
                        + "，new" + permissions(command.active(), command.canRespond(), command.canManage()) + "；" + reason);
        var receipt = jdbc.sql("SELECT * FROM oncall_schedule_member_operation WHERE actor_id=:actor AND operation_key=:key")
                .param("actor", actor).param("key", key).query(operationMapper).single();
        return new ChangeResult(member(schedule, command.userId()), receipt);
    }

    // Call only after the enclosing business transaction has locked all plans in ascending order.
    void lockAccounts(long... ids) {
        var sorted = Arrays.stream(ids).distinct().sorted().boxed().toList();
        if (jdbc.sql("SELECT id FROM sys_user WHERE id IN (:ids) ORDER BY id FOR UPDATE").param("ids", sorted)
                .query(Long.class).list().size() != sorted.size()) throw new ApiException(HttpStatus.NOT_FOUND, "ONCALL_MEMBER_USER_NOT_FOUND", "用户不存在");
    }

    void requireResponder(long schedule, long user) {
        if (jdbc.sql("""
                SELECT m.user_id FROM oncall_schedule_member m JOIN sys_user u ON u.id=m.user_id
                WHERE m.schedule_id=:schedule AND m.user_id=:user AND m.active=TRUE AND m.can_respond=TRUE
                  AND u.status='ACTIVE' AND u.role_code IN ('ADMIN','OPS_MANAGER','ON_CALL') FOR UPDATE
                """).param("schedule", schedule).param("user", user).query(Long.class).optional().isEmpty())
            throw new ApiException(HttpStatus.FORBIDDEN, "ONCALL_PLAN_RESPONSE_FORBIDDEN", "当前用户没有此计划的响应权限");
    }

    void requireManager(long schedule, long actor) {
        String role = requireGlobalManager(actor);
        if (role.equals("ADMIN")) return;
        if (jdbc.sql("SELECT user_id FROM oncall_schedule_member WHERE schedule_id=:schedule AND user_id=:actor AND active=TRUE AND can_manage=TRUE FOR UPDATE")
                .param("schedule", schedule).param("actor", actor).query(Long.class).optional().isEmpty())
            throw new ApiException(HttpStatus.FORBIDDEN, "ONCALL_PLAN_MANAGEMENT_FORBIDDEN", "当前账号没有此计划的管理权限");
    }

    private String requireGlobalManager(long actor) {
        return jdbc.sql("SELECT role_code FROM sys_user WHERE id=:id AND status='ACTIVE' AND role_code IN ('ADMIN','OPS_MANAGER') FOR UPDATE")
                .param("id", actor).query(String.class).optional().orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
                        "ONCALL_PLAN_MANAGEMENT_FORBIDDEN", "当前账号须为活跃管理员或运维经理"));
    }
    private void requireSchedule(long id, boolean lock) {
        jdbc.sql("SELECT id FROM oncall_schedule WHERE id=:id" + (lock ? " FOR UPDATE" : "")).param("id", id).query(Long.class)
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ONCALL_SCHEDULE_NOT_FOUND", "计划不存在"));
    }
    private MemberView member(long schedule, long user) {
        return jdbc.sql("SELECT m.*,u.display_name,u.status,u.role_code FROM oncall_schedule_member m JOIN sys_user u ON u.id=m.user_id WHERE m.schedule_id=:schedule AND m.user_id=:user")
                .param("schedule", schedule).param("user", user).query(memberMapper).single();
    }
    private LocalDateTime now() { return jdbc.sql("SELECT CURRENT_TIMESTAMP(6)").query((rs,n) -> rs.getObject(1, LocalDateTime.class)).single(); }
    private static String key(String value) {
        try { var k = UUID.fromString(value).toString(); if (!k.equals(value)) throw new IllegalArgumentException(); return k; }
        catch (NullPointerException | IllegalArgumentException error) { throw invalid("操作键须为规范UUID"); }
    }
    private static String text(String value) { if (value == null || value.isBlank() || value.length() > 500) throw invalid("说明须为1–500字"); return value.strip(); }
    private static String permissions(boolean active, boolean respond, boolean manage) { return "[active=" + active + ",respond=" + respond + ",manage=" + manage + "]"; }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "ONCALL_MEMBER_INVALID", message); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private static final RowMapper<MemberView> memberMapper = (rs,n) -> new MemberView(rs.getLong("schedule_id"), rs.getLong("user_id"), rs.getString("display_name"),
            rs.getBoolean("active"),rs.getBoolean("can_respond"),rs.getBoolean("can_manage"),rs.getInt("version"),rs.getString("origin"),
            rs.getString("status"),rs.getString("role_code"),rs.getObject("created_at",LocalDateTime.class),rs.getObject("updated_at",LocalDateTime.class));
    private static final RowMapper<Operation> operationMapper = (rs,n) -> new Operation(rs.getLong("schedule_id"),rs.getLong("user_id"),rs.getLong("actor_id"),rs.getString("operation_key"),
            rs.getObject("expected_version",Integer.class),rs.getInt("result_version"),rs.getBoolean("active"),rs.getBoolean("can_respond"),rs.getBoolean("can_manage"),rs.getString("reason"),rs.getObject("committed_at",LocalDateTime.class));
    public record Command(long userId, Integer expectedVersion, boolean active, boolean canRespond, boolean canManage, String operationKey, String reason) {}
    public record MemberView(long scheduleId,long userId,String userName,boolean active,boolean canRespond,boolean canManage,int version,String origin,
                             String accountStatus,String roleCode,LocalDateTime createdAt,LocalDateTime updatedAt) {
        @JsonProperty public boolean effectiveResponse() { return active && canRespond && accountStatus.equals("ACTIVE") && List.of("ADMIN","OPS_MANAGER","ON_CALL").contains(roleCode); }
        @JsonProperty public boolean effectiveManagement() { return active && canManage && accountStatus.equals("ACTIVE") && List.of("ADMIN","OPS_MANAGER").contains(roleCode); }
    }
    public record Operation(long scheduleId,long userId,long actorId,String operationKey,Integer expectedVersion,int resultVersion,boolean active,boolean canRespond,boolean canManage,String reason,LocalDateTime committedAt) {}
    public record ChangeResult(MemberView current,Operation receipt) {}
}
