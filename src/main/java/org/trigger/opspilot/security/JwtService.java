package org.trigger.opspilot.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
                .withClaim("uid", principal.id())
                .withClaim("role", principal.roleCode())
                .withIssuedAt(now)
                .withExpiresAt(now.plus(properties.accessTokenMinutes(), ChronoUnit.MINUTES))
                .sign(algorithm);
    }

    public TokenIdentity verifyIdentity(String token) throws JWTVerificationException {
        var decoded = JWT.require(algorithm)
                .withIssuer("opspilot")
                .build()
                .verify(token);
        JsonNode uid = decoded.getClaim("uid").as(JsonNode.class);
        JsonNode subject = decoded.getClaim("sub").as(JsonNode.class);
        JsonNode expiry = decoded.getClaim("exp").as(JsonNode.class);
        if (uid == null || !uid.isIntegralNumber() || !uid.canConvertToLong() || uid.longValue() <= 0
                || subject == null || !subject.isTextual() || subject.textValue().isBlank()
                || subject.textValue().codePointCount(0, subject.textValue().length()) > 64
                || expiry == null || !expiry.isIntegralNumber() || !expiry.canConvertToLong() || expiry.longValue() <= 0) {
            throw new JWTVerificationException("Invalid token identity or expiry");
        }
        return new TokenIdentity(uid.longValue(), subject.textValue());
    }

    public record TokenIdentity(long userId, String username) { }

    public long expiresInSeconds() {
        return properties.accessTokenMinutes() * 60;
    }
}
