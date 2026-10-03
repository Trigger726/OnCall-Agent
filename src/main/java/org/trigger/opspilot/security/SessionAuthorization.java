package org.trigger.opspilot.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;

/** Immutable authorization snapshot; never retain a raw JWT or password in asynchronous work. */
@Service
public class SessionAuthorization {
    private static final Set<String> INVESTIGATORS = Set.of("ADMIN", "OPS_MANAGER", "ON_CALL");
    private final JdbcClient jdbc;

    public SessionAuthorization(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Lease current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof Lease lease)) {
            throw new CredentialsExpiredException("Verified session required");
        }
        return lease;
    }

    public Lease capture(Long actorId) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            Lease lease = current();
            if (actorId == null || lease.userId() != actorId) throw new CredentialsExpiredException("Actor mismatch");
            return lease;
        }
        // Trusted in-process callers still capture account version before queueing, never at execution.
        if (actorId == null) throw new CredentialsExpiredException("Actor required");
        return jdbc.sql("SELECT id,username,auth_version FROM sys_user WHERE id=:id AND status='ACTIVE'")
                .param("id", actorId)
                .query((rs, row) -> new Lease(rs.getLong("id"), rs.getString("username"), rs.getLong("auth_version"), Instant.MAX))
                .optional().orElseThrow(() -> new CredentialsExpiredException("Account unavailable"));
    }

    public boolean authorized(Lease lease, boolean investigate) { return authorized(lease, investigate, false); }

    public boolean authorized(Lease lease, boolean investigate, boolean lock) {
        if (lease == null || !Instant.now().isBefore(lease.expiresAt())) return false;
        var current = jdbc.sql("SELECT auth_version,status,role_code FROM sys_user WHERE id=:id AND username=:name"
                        + (lock ? " FOR UPDATE" : ""))
                .param("id", lease.userId()).param("name", lease.username())
                .query((rs, row) -> new Account(rs.getLong("auth_version"), rs.getString("status"), rs.getString("role_code")))
                .optional().orElse(null);
        // Recheck expiry after a possible row-lock wait.
        return current != null && "ACTIVE".equals(current.status()) && current.version() == lease.authVersion()
                && Instant.now().isBefore(lease.expiresAt()) && (!investigate || INVESTIGATORS.contains(current.role()));
    }

    public record Lease(long userId, String username, long authVersion, Instant expiresAt) { }
    private record Account(long version, String status, String role) { }
}
