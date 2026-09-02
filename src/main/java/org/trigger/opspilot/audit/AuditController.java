package org.trigger.opspilot.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditController {
    private final JdbcClient jdbcClient;

    public AuditController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR','OPS_MANAGER')")
    public ApiResponse<List<AuditView>> list(@RequestParam(defaultValue = "100") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<AuditView> rows = jdbcClient.sql("""
                        SELECT a.id, u.display_name, a.action, a.target_type, a.target_id,
                               a.detail, a.ip_address, a.created_at
                        FROM audit_log a LEFT JOIN sys_user u ON u.id = a.actor_id
                        ORDER BY a.created_at DESC LIMIT :limit
                        """)
                .param("limit", safeLimit)
                .query((rs, rowNum) -> new AuditView(
                        rs.getLong("id"), rs.getString("display_name"), rs.getString("action"),
                        rs.getString("target_type"), rs.getString("target_id"), rs.getString("detail"),
                        rs.getString("ip_address"), rs.getObject("created_at", LocalDateTime.class)))
                .list();
        return ApiResponse.ok(rows);
    }

    public record AuditView(Long id, String actor, String action, String targetType, String targetId,
                            String detail, String ipAddress, LocalDateTime createdAt) {
    }
}
