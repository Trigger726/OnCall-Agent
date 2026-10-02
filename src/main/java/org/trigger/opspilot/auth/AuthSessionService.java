package org.trigger.opspilot.auth;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.security.UserPrincipal;

@Service
public class AuthSessionService {
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public AuthSessionService(JdbcClient jdbc, PasswordEncoder encoder, AuditService audit) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Transactional
    public void revokeAll(UserPrincipal actor) {
        lockCurrent(actor);
        advance(actor, null);
        audit.record("AUTH_SESSIONS_REVOKED", "USER", actor.id(), "撤销本人全部已签发会话；必须重新登录");
    }

    @Transactional
    public void changePassword(UserPrincipal actor, String currentPassword, String newPassword) {
        var current = lockCurrent(actor);
        if (!PasswordPolicy.validBcryptInput(currentPassword)
                || !encoder.matches(currentPassword, current.passwordHash())) {
            throw new BadCredentialsException("Invalid current credentials");
        }
        if (!PasswordPolicy.validBcryptInput(newPassword) || newPassword.codePointCount(0, newPassword.length()) < 15) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_POLICY",
                    "新密码至少15个字符，UTF-8编码最多72字节；不可包含无效Unicode或空字符");
        }
        if (encoder.matches(newPassword, current.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED", "新密码不能与当前密码相同");
        }
        advance(actor, encoder.encode(newPassword));
        audit.record("AUTH_PASSWORD_CHANGED", "USER", actor.id(), "本人修改密码并撤销全部已签发会话；必须重新登录");
    }

    private Credentials lockCurrent(UserPrincipal actor) {
        var current = jdbc.sql("SELECT password_hash,auth_version,status FROM sys_user WHERE id=:id AND username=:name FOR UPDATE")
                .param("id", actor.id()).param("name", actor.username())
                .query((rs, row) -> new Credentials(rs.getString("password_hash"), rs.getLong("auth_version"), rs.getString("status")))
                .optional().orElseThrow(() -> new CredentialsExpiredException("Account is no longer current"));
        if (!"ACTIVE".equals(current.status()) || current.authVersion() != actor.authVersion()) {
            throw new CredentialsExpiredException("Session is no longer current");
        }
        if (current.authVersion() == Long.MAX_VALUE) {
            throw new ApiException(HttpStatus.CONFLICT, "AUTH_VERSION_EXHAUSTED", "账号会话版本已达上限，请联系管理员");
        }
        return current;
    }

    private void advance(UserPrincipal actor, String passwordHash) {
        String sql = passwordHash == null
                ? "UPDATE sys_user SET auth_version=auth_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND auth_version=:version"
                : "UPDATE sys_user SET password_hash=:password,auth_version=auth_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND auth_version=:version";
        var command = jdbc.sql(sql).param("id", actor.id()).param("version", actor.authVersion());
        if (passwordHash != null) command.param("password", passwordHash);
        if (command.update() != 1) throw new CredentialsExpiredException("Session is no longer current");
    }

    private record Credentials(String passwordHash, long authVersion, String status) { }
}
