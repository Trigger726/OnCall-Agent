package org.trigger.opspilot.oncall;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class OnCallService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbcClient;

    public OnCallService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<OnCallView> current() {
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        return jdbcClient.sql("""
                        SELECT schedule.id AS schedule_id, schedule.name AS schedule_name, r.id AS resource_id,
                               r.name AS resource_name, u.id AS user_id, u.display_name, u.department,
                               shift.starts_at, shift.ends_at, shift.override_flag
                        FROM oncall_schedule schedule
                        JOIN cmdb_resource r ON r.id = schedule.service_resource_id
                        LEFT JOIN oncall_shift shift ON shift.id = (
                          SELECT candidate.id FROM oncall_shift candidate
                          JOIN sys_user candidate_user ON candidate_user.id = candidate.user_id
                          WHERE candidate.schedule_id = schedule.id AND candidate_user.status = 'ACTIVE'
                            AND candidate.starts_at <= :now
                            AND candidate.ends_at > :now
                          ORDER BY candidate.override_flag DESC, candidate.starts_at DESC, candidate.id DESC LIMIT 1)
                        LEFT JOIN sys_user u ON u.id = shift.user_id
                        WHERE schedule.active = TRUE ORDER BY schedule.id
                        """).param("now", now)
                .query((rs, rowNum) -> new OnCallView(
                        rs.getLong("schedule_id"), rs.getString("schedule_name"), rs.getLong("resource_id"),
                        rs.getString("resource_name"), nullableLong(rs, "user_id"), rs.getString("display_name"),
                        rs.getString("department"), rs.getObject("starts_at", LocalDateTime.class),
                        rs.getObject("ends_at", LocalDateTime.class), rs.getBoolean("override_flag")))
                .list();
    }

    public List<PolicyView> policies() {
        return jdbcClient.sql("""
                        SELECT p.id, p.name, p.severity, r.name AS resource_name, s.step_order, s.delay_minutes,
                               s.target_type, s.target_ref
                        FROM escalation_policy p JOIN cmdb_resource r ON r.id = p.service_resource_id
                        JOIN escalation_step s ON s.policy_id = p.id
                        WHERE p.active = TRUE ORDER BY p.id, s.step_order
                        """)
                .query((rs, rowNum) -> new PolicyView(
                        rs.getLong("id"), rs.getString("name"), rs.getString("severity"),
                        rs.getString("resource_name"),
                        rs.getInt("step_order"), rs.getInt("delay_minutes"), rs.getString("target_type"),
                        rs.getString("target_ref"))).list();
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record OnCallView(Long scheduleId, String scheduleName, Long resourceId, String resourceName,
                             Long userId, String userName, String department, LocalDateTime startsAt,
                             LocalDateTime endsAt, boolean override) {
    }

    public record PolicyView(Long policyId, String policyName, String severity, String resourceName, int step,
                             int delayMinutes, String targetType, String targetRef) {
    }
}
