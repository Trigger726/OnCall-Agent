package org.trigger.opspilot.reference;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/reference")
public class ReferenceController {
    private final JdbcClient jdbcClient;

    public ReferenceController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @GetMapping("/users")
    public ApiResponse<List<UserOption>> users() {
        return ApiResponse.ok(jdbcClient.sql("""
                        SELECT id, username, display_name, role_code, department FROM sys_user
                        WHERE status = 'ACTIVE' ORDER BY display_name
                        """)
                .query((rs, rowNum) -> new UserOption(
                        rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                        rs.getString("role_code"), rs.getString("department"))).list());
    }

    @GetMapping("/runbooks")
    public ApiResponse<List<RunbookView>> runbooks() {
        return ApiResponse.ok(jdbcClient.sql("""
                        SELECT id, resource_type, symptom_keyword, title, content, updated_at
                        FROM runbook WHERE enabled = TRUE ORDER BY title
                        """)
                .query((rs, rowNum) -> new RunbookView(
                        rs.getLong("id"), rs.getString("resource_type"), rs.getString("symptom_keyword"),
                        rs.getString("title"), rs.getString("content"),
                        rs.getObject("updated_at", LocalDateTime.class))).list());
    }

    public record UserOption(Long id, String username, String displayName, String roleCode, String department) {
    }

    public record RunbookView(Long id, String resourceType, String keyword, String title,
                              String content, LocalDateTime updatedAt) {
    }
}
