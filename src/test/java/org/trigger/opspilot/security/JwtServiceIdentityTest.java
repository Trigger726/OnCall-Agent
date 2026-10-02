package org.trigger.opspilot.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceIdentityTest {
    private static final String SECRET = "identity-contract-test-only-not-a-runtime-key";
    private final JwtService service = new JwtService(new JwtProperties(SECRET, 30));

    @Test void shouldIssueDistinctIdentifiersForIndependentLogins() {
        var actor = new UserPrincipal(1L, "identity-test", "unused", "Identity", "ON_CALL", true, 0);
        String first = service.createToken(actor), second = service.createToken(actor);
        assertThat(first).isNotEqualTo(second);
        assertThat(UUID.fromString(JWT.decode(first).getId())).isNotEqualTo(UUID.fromString(JWT.decode(second).getId()));
        assertThat(service.verifyIdentity(first)).isEqualTo(service.verifyIdentity(second));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 2147483648L, 9007199254740993L, Long.MAX_VALUE})
    void shouldPreserveExactIssuedSessionVersion(long version) {
        String token = service.createToken(new UserPrincipal(1L, "identity-test", "unused", "Identity", "ON_CALL", true, version));
        assertThat(service.verifyIdentity(token).authVersion()).isEqualTo(version);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "null", "\"0\"", "0.0", "0.9", "-1", "true", "[]", "{}", "9223372036854775808"})
    void shouldRequirePresentNonNegativeIntegralSessionVersion(String version) {
        String claim = version.equals("missing") ? "" : ",\"sv\":" + version;
        String payload = "{\"iss\":\"opspilot\",\"uid\":1,\"sub\":\"identity-test\",\"exp\":" + future() + claim + "}";
        assertThatThrownBy(() -> service.verifyIdentity(SignedJwtFixture.sign(payload, SECRET)))
                .isInstanceOf(JWTVerificationException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2147483648L, 9007199254740993L, Long.MAX_VALUE})
    void shouldPreserveExactIssuedLongIdentity(long id) {
        String token = service.createToken(new UserPrincipal(id, "identity-test", "unused", "Identity", "ON_CALL", true, 0));
        assertThat(service.verifyIdentity(token)).isEqualTo(new JwtService.TokenIdentity(id, "identity-test", 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "null", "\"1\"", "1.0", "1.9", "0", "-1", "true", "[]", "{}", "9223372036854775808"})
    void shouldRejectMissingOrNonPositiveIntegralUid(String uid) {
        String claim = uid.equals("missing") ? "" : "\"uid\":" + uid + ",";
        String token = signed("{" + claim + "\"sub\":\"identity-test\",\"exp\":" + future() + "}");
        assertThatThrownBy(() -> service.verifyIdentity(token)).isInstanceOf(JWTVerificationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "null", "\"\"", "\"   \"", "1", "true", "[]", "{}"})
    void shouldRejectMissingBlankOrNonTextSubject(String subject) {
        String claim = subject.equals("missing") ? "" : "\"sub\":" + subject + ",";
        String token = signed("{" + claim + "\"uid\":1,\"exp\":" + future() + "}");
        assertThatThrownBy(() -> service.verifyIdentity(token)).isInstanceOf(JWTVerificationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "null", "\"4102444800\"", "4102444800.5", "0", "-1", "true", "[]", "{}", "9223372036854775808"})
    void shouldRequireRealIntegralExpiry(String expiry) {
        String claim = expiry.equals("missing") ? "" : ",\"exp\":" + expiry;
        String token = signed("{\"uid\":1,\"sub\":\"identity-test\"" + claim + "}");
        assertThatThrownBy(() -> service.verifyIdentity(token)).isInstanceOf(JWTVerificationException.class);
    }

    @Test void shouldUseUnicodeCharactersRatherThanUtf16UnitsForSubjectLimit() {
        String username = "𐐷".repeat(64);
        String token = service.createToken(new UserPrincipal(1L, username, "unused", "Identity", "ON_CALL", true, 0));
        assertThat(service.verifyIdentity(token).username()).isEqualTo(username);
        String oversized = signed("{\"uid\":1,\"sub\":\"" + username + "𐐷\",\"exp\":" + future() + "}");
        assertThatThrownBy(() -> service.verifyIdentity(oversized)).isInstanceOf(JWTVerificationException.class);
    }

    @ParameterizedTest
    @CsvSource({"exp,9223372036854775807", "exp,-9223372036854775808",
            "iat,9223372036854775807", "iat,-9223372036854775808",
            "nbf,9223372036854775807", "nbf,-9223372036854775808"})
    void shouldRejectOutOfRangeNumericDateAsVerificationFailure(String field, long seconds) {
        String payload = "{\"iss\":\"opspilot\",\"uid\":1,\"sub\":\"identity-test\",\"exp\":" + future();
        if (field.equals("exp")) payload = "{\"iss\":\"opspilot\",\"uid\":1,\"sub\":\"identity-test\"";
        String token = SignedJwtFixture.sign(payload + ",\"" + field + "\":" + seconds + "}", SECRET);
        assertThatThrownBy(() -> service.verifyIdentity(token)).isInstanceOf(JWTVerificationException.class);
    }

    @Test void shouldRejectOversizedSubjectExpiredWrongIssuerAndWrongSignature() {
        String oversized = signed("{\"uid\":1,\"sub\":\"" + "a".repeat(65) + "\",\"exp\":" + future() + "}");
        String expired = signed("{\"uid\":1,\"sub\":\"identity-test\",\"exp\":1}");
        String issuer = JWT.create().withIssuer("different-system").withSubject("identity-test")
                .withClaim("uid", 1).withClaim("sv", 0).withExpiresAt(Instant.now().plusSeconds(60)).sign(Algorithm.HMAC256(SECRET));
        String signature = JWT.create().withIssuer("opspilot").withSubject("identity-test")
                .withClaim("uid", 1).withClaim("sv", 0).withExpiresAt(Instant.now().plusSeconds(60)).sign(Algorithm.HMAC256("different-test-key"));
        for (String token : new String[]{oversized, expired, issuer, signature}) {
            assertThatThrownBy(() -> service.verifyIdentity(token)).isInstanceOf(JWTVerificationException.class);
        }
    }

    private static long future() { return Instant.now().plusSeconds(60).getEpochSecond(); }
    private static String signed(String payload) {
        return SignedJwtFixture.sign("{\"iss\":\"opspilot\",\"sv\":0," + payload.substring(1), SECRET);
    }
}
