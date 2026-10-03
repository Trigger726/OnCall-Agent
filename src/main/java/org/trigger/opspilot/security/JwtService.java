package org.trigger.opspilot.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class JwtService {
    private final JwtProperties properties;
    private final Algorithm algorithm;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.algorithm = Algorithm.HMAC256(properties.jwtSecret());
    }

    public String createToken(UserPrincipal principal) {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuer("opspilot")
                .withSubject(principal.username())
                .withJWTId(UUID.randomUUID().toString())
                .withClaim("uid", principal.id())
                .withClaim("sv", principal.authVersion())
                .withClaim("role", principal.roleCode())
                .withIssuedAt(now)
                .withExpiresAt(now.plus(properties.accessTokenMinutes(), ChronoUnit.MINUTES))
                .sign(algorithm);
    }

    public TokenIdentity verifyIdentity(String token) throws JWTVerificationException {
        var lease = verifySession(token);
        return new TokenIdentity(lease.userId(), lease.username(), lease.authVersion());
    }

    public SessionAuthorization.Lease verifySession(String token) throws JWTVerificationException {
        DecodedJWT decoded;
        try {
            decoded = JWT.require(algorithm)
                    .withIssuer("opspilot")
                    .build()
                    .verify(token);
        } catch (DateTimeException exception) {
            // The verifier parses NumericDate before checking the signature.
            throw new JWTVerificationException("Invalid token date", exception);
        }
        JsonNode uid = decoded.getClaim("uid").as(JsonNode.class);
        JsonNode subject = decoded.getClaim("sub").as(JsonNode.class);
        JsonNode expiry = decoded.getClaim("exp").as(JsonNode.class);
        JsonNode version = decoded.getClaim("sv").as(JsonNode.class);
        if (uid == null || !uid.isIntegralNumber() || !uid.canConvertToLong() || uid.longValue() <= 0
                || subject == null || !subject.isTextual() || subject.textValue().isBlank()
                || subject.textValue().codePointCount(0, subject.textValue().length()) > 64
                || expiry == null || !expiry.isIntegralNumber() || !expiry.canConvertToLong() || expiry.longValue() <= 0
                || version == null || !version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0) {
            throw new JWTVerificationException("Invalid token identity or expiry");
        }
        return new SessionAuthorization.Lease(uid.longValue(), subject.textValue(), version.longValue(), decoded.getExpiresAtAsInstant());
    }

    public record TokenIdentity(long userId, String username, long authVersion) { }

    public long expiresInSeconds() {
        return properties.accessTokenMinutes() * 60;
    }
}
