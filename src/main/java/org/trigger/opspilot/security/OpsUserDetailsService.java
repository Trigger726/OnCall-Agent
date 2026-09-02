package org.trigger.opspilot.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class OpsUserDetailsService implements UserDetailsService {
    private final JdbcClient jdbcClient;

    public OpsUserDetailsService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public UserPrincipal loadUserByUsername(String username) throws UsernameNotFoundException {
        return jdbcClient.sql("""
                        SELECT id, username, password_hash, display_name, role_code, status
                        FROM sys_user WHERE username = :username
                        """)
                .param("username", username)
                .query((rs, rowNum) -> new UserPrincipal(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("display_name"),
                        rs.getString("role_code"),
                        "ACTIVE".equals(rs.getString("status"))))
                .optional()
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
    }
}
