package org.trigger.opspilot.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real servlet requests and committed fixtures shared by H2 and MySQL. */
public final class AuthSessionHttpScenarios implements AutoCloseable {
    private static final String OLD_PASSWORD = "OpsPilot@2026";
    private static final String NEW_PASSWORD = "A new operational passphrase";
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final PasswordEncoder encoder;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String base;
    private final String username = "cp65_" + UUID.randomUUID().toString().replace("-", "");
    private final long userId;

    public AuthSessionHttpScenarios(JdbcClient jdbc, ObjectMapper mapper, PasswordEncoder encoder, int port) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.encoder = encoder;
        base = "http://127.0.0.1:" + port;
        jdbc.sql("""
                INSERT INTO sys_user(username,password_hash,display_name,role_code,status)
                SELECT :name,password_hash,'Session revocation fixture','OPS_MANAGER','ACTIVE'
                FROM sys_user WHERE username='admin'
                """).param("name", username).update();
        userId = jdbc.sql("SELECT id FROM sys_user WHERE username=:name")
                .param("name", username).query(Long.class).single();
    }

    public void logoutAllRevokesTwoTokensPermanently() throws Exception {
        String first = login(OLD_PASSWORD), second = login(OLD_PASSWORD);
        assertThat(first).isNotEqualTo(second);
        assertThat(call("GET", "/auth/me", first, null).statusCode()).isEqualTo(200);
        var result = call("POST", "/auth/logout-all", first, Map.of());
        System.out.printf("CP65_LOGOUT status=%d%n", result.statusCode());
        assertThat(result.statusCode()).isEqualTo(200);
        assertRevoked(first);
        assertRevoked(second);
        String fresh = login(OLD_PASSWORD);
        assertThat(call("GET", "/auth/me", fresh, null).statusCode()).isEqualTo(200);
        assertRevoked(first); // Fresh login must not restore an old token.
        assertThat(version()).isEqualTo(1);
        assertAudit("AUTH_SESSIONS_REVOKED");
    }

    public void passwordChangeRevokesTokensAndRequiresNewPassword() throws Exception {
        String first = login(OLD_PASSWORD), second = login(OLD_PASSWORD);
        assertThat(first).isNotEqualTo(second);
        var changed = call("POST", "/auth/password", first,
                Map.of("currentPassword", OLD_PASSWORD, "newPassword", NEW_PASSWORD));
        System.out.printf("CP65_PASSWORD status=%d%n", changed.statusCode());
        assertThat(changed.statusCode()).isEqualTo(200);
        assertRevoked(first);
        assertRevoked(second);
        assertThat(call("POST", "/auth/login", null,
                Map.of("username", username, "password", OLD_PASSWORD)).statusCode()).isEqualTo(401);
        assertThat(call("GET", "/auth/me", login(NEW_PASSWORD), null).statusCode()).isEqualTo(200);
        String hash = hash();
        assertThat(hash).isNotEqualTo(NEW_PASSWORD).startsWith("$2");
        assertThat(encoder.matches(NEW_PASSWORD, hash)).isTrue();
        assertThat(version()).isEqualTo(1);
        assertAudit("AUTH_PASSWORD_CHANGED");
    }

    public void invalidPasswordChangesDoNotWrite() throws Exception {
        String token = login(OLD_PASSWORD), hash = hash();
        for (var entry : List.of(
                Map.entry(Map.of("currentPassword", "wrong", "newPassword", NEW_PASSWORD), 401),
                Map.entry(Map.of("currentPassword", OLD_PASSWORD, "newPassword", "short"), 400),
                Map.entry(Map.of("currentPassword", OLD_PASSWORD, "newPassword", "界".repeat(25)), 400),
                Map.entry(Map.of("currentPassword", OLD_PASSWORD, "newPassword", "a".repeat(73)), 400),
                Map.entry(Map.of("currentPassword", OLD_PASSWORD, "newPassword", ""), 400))) {
            var response = call("POST", "/auth/password", token, entry.getKey());
            assertThat(response.statusCode()).isEqualTo(entry.getValue());
            if (entry.getValue() == 401) assertThat(mapper.readTree(response.body()).path("error").path("code").asText())
                    .isEqualTo("AUTHENTICATION_FAILED");
        }
        assertThat(hash()).isEqualTo(hash);
        assertThat(version()).isZero();
        assertThat(auditCount()).isZero();
        assertThat(call("GET", "/auth/me", token, null).statusCode()).isEqualTo(200);
    }

    public void concurrentRevocationsOnlyAdvanceOnce() throws Exception {
        String token = login(OLD_PASSWORD);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(); return call("POST", "/auth/logout-all", token, Map.of()); });
            var second = pool.submit(() -> { start.await(); return call("POST", "/auth/logout-all", token, Map.of()); });
            start.countDown();
            var results = List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS), second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results.stream().map(java.net.http.HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 401);
            for (var response : results) if (response.statusCode() == 401) {
                assertThat(mapper.readTree(response.body()).path("error").path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(version()).isEqualTo(1);
        assertThat(auditCount()).isEqualTo(1);
        assertRevoked(token);
    }

    public void revocationDoesNotAffectAnotherAccount() throws Exception {
        String mine = login(OLD_PASSWORD);
        var other = call("POST", "/auth/login", null, Map.of("username", "admin", "password", OLD_PASSWORD));
        String otherToken = mapper.readTree(other.body()).path("data").path("accessToken").asText();
        assertThat(call("POST", "/auth/logout-all", mine, Map.of("userId", 1)).statusCode()).isEqualTo(200);
        assertRevoked(mine);
        assertThat(call("GET", "/auth/me", otherToken, null).statusCode()).isEqualTo(200);
        assertAudit("AUTH_SESSIONS_REVOKED");
    }

    public void rejectsLegacyAndMalformedVersions(JwtProperties properties) throws Exception {
        var valid = mapper.createObjectNode().put("iss", "opspilot").put("sub", username)
                .put("uid", userId).put("exp", java.time.Instant.now().plusSeconds(60).getEpochSecond());
        for (String value : new String[]{"missing", "null", "\"0\"", "0.0", "-1", "true", "[]", "9223372036854775808"}) {
            var payload = valid.deepCopy();
            if (!value.equals("missing")) payload.set("sv", mapper.readTree(value));
            assertRevoked(SignedJwtFixture.sign(payload.toString(), properties.jwtSecret()));
        }
        assertThat(version()).isZero();
        assertThat(auditCount()).isZero();
        String future = SignedJwtFixture.sign(valid.deepCopy().put("sv", 1).toString(), properties.jwtSecret());
        assertRevoked(future);
        String correct = SignedJwtFixture.sign(valid.deepCopy().put("sv", 0).toString(), properties.jwtSecret());
        assertThat(call("GET", "/auth/me", correct, null).statusCode()).isEqualTo(200);
    }

    public void supportsUnicodeAndRejectsUnchangedPassword() throws Exception {
        String unicode = "𐐷".repeat(15);
        assertThat(call("POST", "/auth/password", login(OLD_PASSWORD),
                Map.of("currentPassword", OLD_PASSWORD, "newPassword", unicode)).statusCode()).isEqualTo(200);
        String current = login(unicode), hash = hash();
        assertThat(call("POST", "/auth/password", current,
                Map.of("currentPassword", unicode, "newPassword", unicode)).statusCode()).isEqualTo(400);
        assertThat(hash()).isEqualTo(hash);
        assertThat(version()).isEqualTo(1);
        assertThat(auditCount()).isEqualTo(1);
        assertThat(call("GET", "/auth/me", current, null).statusCode()).isEqualTo(200);
    }

    public void versionExhaustionDoesNotWrap() throws Exception {
        jdbc.sql("UPDATE sys_user SET auth_version=:version WHERE id=:id")
                .param("version", Long.MAX_VALUE).param("id", userId).update();
        String token = login(OLD_PASSWORD);
        var result = call("POST", "/auth/logout-all", token, Map.of());
        assertThat(result.statusCode()).isEqualTo(409);
        assertThat(mapper.readTree(result.body()).path("error").path("code").asText()).isEqualTo("AUTH_VERSION_EXHAUSTED");
        assertThat(version()).isEqualTo(Long.MAX_VALUE);
        assertThat(auditCount()).isZero();
        assertThat(call("GET", "/auth/me", token, null).statusCode()).isEqualTo(200);
    }

    public void failedAuditRollsBackPasswordAndVersion() throws Exception {
        String token = login(OLD_PASSWORD), before = hash();
        var result = call("POST", "/auth/password", token,
                Map.of("currentPassword", OLD_PASSWORD, "newPassword", NEW_PASSWORD));
        assertThat(result.statusCode()).isEqualTo(409);
        assertThat(mapper.readTree(result.body()).path("error").path("code").asText()).isEqualTo("FORCED_AUDIT_FAILURE");
        assertThat(hash()).isEqualTo(before);
        assertThat(version()).isZero();
        assertThat(auditCount()).isZero();
        assertThat(call("GET", "/auth/me", token, null).statusCode()).isEqualTo(200);
        login(OLD_PASSWORD);
        assertThat(call("POST", "/auth/login", null,
                Map.of("username", username, "password", NEW_PASSWORD)).statusCode()).isEqualTo(401);
    }

    public void rejectsBcryptTruncationAliasesAtLogin() throws Exception {
        String boundary = "界".repeat(24); // Exactly 72 UTF-8 bytes, not 72 Java characters.
        assertThat(call("POST", "/auth/password", login(OLD_PASSWORD),
                Map.of("currentPassword", OLD_PASSWORD, "newPassword", boundary)).statusCode()).isEqualTo(200);
        String current = login(boundary), before = hash();
        var extended = call("POST", "/auth/login", null, Map.of("username", username, "password", boundary + "x"));
        System.out.printf("CP65_BYTE_BOUNDARY extendedLogin=%d%n", extended.statusCode());
        assertThat(extended.statusCode()).isEqualTo(401);
        assertThat(call("POST", "/auth/password", current,
                Map.of("currentPassword", boundary + "x", "newPassword", NEW_PASSWORD)).statusCode()).isEqualTo(401);
        assertThat(hash()).isEqualTo(before);
        assertThat(version()).isEqualTo(1);
        assertThat(auditCount()).isEqualTo(1);
    }

    private void assertRevoked(String token) throws Exception {
        long notes = jdbc.sql("SELECT COUNT(*) FROM incident_timeline WHERE actor_id=:id")
                .param("id", userId).query(Long.class).single(), audits = auditCount();
        for (var result : List.of(call("GET", "/auth/me", token, null),
                call("POST", "/incidents/1/notes", token, Map.of("content", "revoked token must not write")))) {
            assertThat(result.statusCode()).isEqualTo(401);
            JsonNode json = mapper.readTree(result.body());
            assertThat(json.path("success").asBoolean()).isFalse();
            assertThat(json.path("error").path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM incident_timeline WHERE actor_id=:id")
                .param("id", userId).query(Long.class).single()).isEqualTo(notes);
        assertThat(auditCount()).isEqualTo(audits);
    }

    private void assertAudit(String action) {
        var rows = jdbc.sql("SELECT action,detail,ip_address FROM audit_log WHERE actor_id=:id")
                .param("id", userId).query().listOfRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("action")).isEqualTo(action);
        assertThat(rows.get(0).get("ip_address")).isEqualTo("127.0.0.1");
        assertThat(rows.get(0).get("detail").toString()).doesNotContain(OLD_PASSWORD, NEW_PASSWORD, hash(), "Bearer");
    }

    private long version() { return jdbc.sql("SELECT auth_version FROM sys_user WHERE id=:id").param("id", userId).query(Long.class).single(); }
    private String hash() { return jdbc.sql("SELECT password_hash FROM sys_user WHERE id=:id").param("id", userId).query(String.class).single(); }
    private long auditCount() { return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE actor_id=:id").param("id", userId).query(Long.class).single(); }
    private String login(String password) throws Exception {
        var result = call("POST", "/auth/login", null, Map.of("username", username, "password", password));
        assertThat(result.statusCode()).isEqualTo(200);
        return mapper.readTree(result.body()).path("data").path("accessToken").asText();
    }
    private HttpResponse<String> call(String method, String path, String token, Map<String, ?> body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + "/api/v1" + path)).timeout(Duration.ofSeconds(8));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    @Override public void close() {
        jdbc.sql("DELETE FROM incident_timeline WHERE actor_id=:id").param("id", userId).update();
        jdbc.sql("DELETE FROM audit_log WHERE actor_id=:id").param("id", userId).update();
        jdbc.sql("DELETE FROM sys_user WHERE id=:id").param("id", userId).update();
    }
}
