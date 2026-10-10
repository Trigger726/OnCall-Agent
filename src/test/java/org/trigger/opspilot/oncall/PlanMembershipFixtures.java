package org.trigger.opspilot.oncall;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Explicit test setup only; new production plans/accounts never auto-enrol. */
public final class PlanMembershipFixtures {
    private PlanMembershipFixtures() {}

    public static void grant(JdbcClient jdbc, long schedule, long... users) {
        for (long user : users) {
            jdbc.sql("""
                    INSERT INTO oncall_schedule_member(schedule_id,user_id,active,can_respond,can_manage,origin)
                    SELECT :schedule,id,TRUE,TRUE,CASE WHEN role_code IN ('ADMIN','OPS_MANAGER') THEN TRUE ELSE FALSE END,'EXPLICIT'
                    FROM sys_user WHERE id=:user AND status='ACTIVE' AND role_code IN ('ADMIN','OPS_MANAGER','ON_CALL')
                    """).param("schedule", schedule).param("user", user).update();
        }
    }
}
