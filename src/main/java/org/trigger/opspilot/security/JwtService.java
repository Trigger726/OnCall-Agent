package org.trigger.opspilot.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
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

    public String verifyAndGetUsername(String token) throws JWTVerificationException {
        return JWT.require(algorithm)
                .withIssuer("opspilot")
                .build()
                .verify(token)
                .getSubject();
    }

    public long expiresInSeconds() {
        return properties.accessTokenMinutes() * 60;
    }
}
