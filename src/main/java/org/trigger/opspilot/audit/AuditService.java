package org.trigger.opspilot.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.trigger.opspilot.security.UserPrincipal;

@Service
public class AuditService {
    private final JdbcClient jdbcClient;
    private final HttpServletRequest request;

    public AuditService(JdbcClient jdbcClient, HttpServletRequest request) {
        this.jdbcClient = jdbcClient;
        this.request = request;
    }

    public void record(String action, String targetType, Object targetId, String detail) {
        recordAs(currentUserId(), currentIp(), action, targetType, targetId, detail);
    }

    public void recordAs(Long actorId, String ipAddress, String action,
                         String targetType, Object targetId, String detail) {
        jdbcClient.sql("""
                        INSERT INTO audit_log(actor_id, action, target_type, target_id, detail, ip_address)
                        VALUES (:actorId, :action, :targetType, :targetId, :detail, :ip)
                        """)
                .param("actorId", actorId)
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId == null ? null : targetId.toString())
                .param("detail", detail)
                .param("ip", ipAddress)
                .update();
    }

    public Long currentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null
                ? null : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return principal instanceof UserPrincipal user ? user.id() : null;
    }

    public String currentIp() {
        return request.getRemoteAddr();
    }
}
