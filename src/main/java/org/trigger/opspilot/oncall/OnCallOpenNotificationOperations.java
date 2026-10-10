package org.trigger.opspilot.oncall;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.trigger.opspilot.common.ApiException;
import java.time.LocalDateTime;

/** Global counts are administrator-only; never return recipient identities or provider secrets. */
@Service
public class OnCallOpenNotificationOperations {
    private final JdbcClient jdbc;
    private final OnCallOpenNotificationProperties properties;
    private final OnCallOpenNotificationJob job;
    public OnCallOpenNotificationOperations(JdbcClient jdbc,OnCallOpenNotificationProperties properties,OnCallOpenNotificationJob job){this.jdbc=jdbc;this.properties=properties;this.job=job;}
    public View get(long actor){
        try{
            if(jdbc.sql("SELECT id FROM sys_user WHERE id=:id AND status='ACTIVE' AND role_code='ADMIN'").param("id",actor).query(Long.class).optional().isEmpty())
                throw new ApiException(HttpStatus.FORBIDDEN,"ONCALL_OPEN_NOTIFICATION_OPERATIONS_FORBIDDEN","只有当前有效管理员可读取全局通知运行状态");
            var queue=jdbc.sql("""
                    SELECT CURRENT_TIMESTAMP(6) AS observed_at,COUNT(*) AS total,
                      COALESCE(SUM(CASE WHEN status='PENDING' THEN 1 ELSE 0 END),0) AS pending,
                      COALESCE(SUM(CASE WHEN status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(6) THEN 1 ELSE 0 END),0) AS pending_due,
                      COALESCE(SUM(CASE WHEN status='CLAIMED' THEN 1 ELSE 0 END),0) AS claimed,
                      COALESCE(SUM(CASE WHEN status='CLAIMED' AND lease_until<=CURRENT_TIMESTAMP(6) THEN 1 ELSE 0 END),0) AS expired_leases,
                      COALESCE(SUM(CASE WHEN status='DELIVERED' THEN 1 ELSE 0 END),0) AS delivered,
                      COALESCE(SUM(CASE WHEN status='FAILED' THEN 1 ELSE 0 END),0) AS failed,
                      COALESCE(SUM(CASE WHEN status='SKIPPED' THEN 1 ELSE 0 END),0) AS skipped,
                      MIN(CASE WHEN status='PENDING' THEN created_at ELSE NULL END) AS oldest_pending_created_at
                    FROM oncall_open_handoff_notification
                    """).query((rs,n)->new QueueView("SHARED_DATABASE_AT_STATEMENT",properties.enabled(),rs.getObject("observed_at",LocalDateTime.class),rs.getLong("total"),
                    rs.getLong("pending"),rs.getLong("pending_due"),rs.getLong("claimed"),rs.getLong("expired_leases"),rs.getLong("delivered"),rs.getLong("failed"),rs.getLong("skipped"),rs.getObject("oldest_pending_created_at",LocalDateTime.class))).single();
            return new View(queue,job.snapshot()); // Database and local executor are separate observations, not a cluster health claim.
        }catch(DataAccessException error){throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"ONCALL_OPEN_NOTIFICATION_OPERATIONS_UNAVAILABLE","通知运行事实暂不可读取；不返回零积压或旧快照");}
    }
    public record QueueView(String scope,boolean enabled,LocalDateTime databaseNow,long total,long pending,long pendingDue,long claimed,long expiredLeases,
                            long delivered,long failed,long skipped,LocalDateTime oldestPendingCreatedAt) {}
    public record View(QueueView queue,OnCallOpenNotificationJob.WorkerView worker) {}
}
