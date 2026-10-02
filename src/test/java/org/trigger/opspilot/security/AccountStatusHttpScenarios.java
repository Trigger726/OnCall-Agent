package org.trigger.opspilot.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared H2/MySQL scenarios: real sockets and committed account changes, not mock principals. */
public final class AccountStatusHttpScenarios implements AutoCloseable {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String base;
    private final String username = "cp59_" + UUID.randomUUID().toString().replace("-", "");
    private final long userId;

    public AccountStatusHttpScenarios(JdbcClient jdbc, ObjectMapper mapper, int port) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.base = "http://127.0.0.1:" + port;
        assertThat(jdbc.sql("""
                INSERT INTO sys_user (username, password_hash, display_name, role_code, status)
                SELECT :username, password_hash, 'HTTP account fixture', 'OPS_MANAGER', 'ACTIVE'
                FROM sys_user WHERE username = 'admin'
                """).param("username", username).update()).isEqualTo(1);
        userId = jdbc.sql("SELECT id FROM sys_user WHERE username = :username")
                .param("username", username).query(Long.class).single();
    }

    public void disabledReadsAndLogin() throws Exception {
        String token = login();
        List<String> paths = List.of("/api/v1/auth/me", "/api/v1/dashboard", "/api/v1/incidents/1",
                "/api/v1/cmdb/topology", "/api/v1/assistant/sessions", "/api/v1/slo/objectives",
                exportPath());
        for (String path : paths) assertThat(request("GET", path, token, null).statusCode()).as(path).isEqualTo(200);
        setStatus("DISABLED"); // Autocommit completes before another server thread handles HTTP.
        for (String path : paths) securityError(request("GET", path, token, null), 401, "AUTHENTICATION_REQUIRED", path);
        securityError(request("POST", "/api/v1/auth/login", null,
                Map.of("username", username, "password", "OpsPilot@2026")), 401, "AUTHENTICATION_FAILED", "disabled login");
        setStatus("ACTIVE");
        // This policy is current account eligibility, not permanent JWT revocation on re-enable.
        assertThat(request("GET", "/api/v1/auth/me", token, null).statusCode()).isEqualTo(200);
    }

    public void disabledWriteHasNoSideEffects() throws Exception {
        String token = login();
        setStatus("DISABLED");
        long notes = count("incident_timeline");
        long audits = count("audit_log");
        securityError(request("POST", "/api/v1/incidents/1/notes", token,
                Map.of("content", "Disabled actor must not write a note", "evidenceRef", "cp59:" + userId)),
                401, "AUTHENTICATION_REQUIRED", "disabled note");
        assertThat(count("incident_timeline")).isEqualTo(notes);
        assertThat(count("audit_log")).isEqualTo(audits);
    }

    public void removedAccountCannotUseOldToken() throws Exception {
        String token = login();
        assertThat(jdbc.sql("DELETE FROM sys_user WHERE id = :id").param("id", userId).update()).isEqualTo(1);
        securityError(request("GET", "/api/v1/auth/me", token, null), 401, "AUTHENTICATION_REQUIRED", "removed account");
    }

    public void activeRoleUsesCurrentDatabaseAuthority() throws Exception {
        String token = login();
        String path = exportPath();
        assertThat(request("GET", path, token, null).statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("UPDATE sys_user SET role_code = 'AUDITOR' WHERE id = :id").param("id", userId).update()).isEqualTo(1);
        var me = request("GET", "/api/v1/auth/me", token, null);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(me.body()).path("data").path("roleCode").asText()).isEqualTo("AUDITOR");
        securityError(request("GET", path, token, null), 403, "ACCESS_DENIED", "current role downgrade");
        assertThat(jdbc.sql("UPDATE sys_user SET role_code = 'OPS_MANAGER' WHERE id = :id").param("id", userId).update()).isEqualTo(1);
        assertThat(request("GET", path, token, null).statusCode()).isEqualTo(200);
    }

    private String exportPath() {
        int version = jdbc.sql("SELECT version FROM service_slo_objective WHERE id = 1").query(Integer.class).single();
        return "/api/v1/slo/objectives/1/versions/" + version + "/prometheus-rules";
    }

    private String login() throws Exception {
        var response = request("POST", "/api/v1/auth/login", null,
                Map.of("username", username, "password", "OpsPilot@2026"));
        assertThat(response.statusCode()).as("real HTTP login").isEqualTo(200);
        String token = mapper.readTree(response.body()).path("data").path("accessToken").asText();
        assertThat(token.isBlank()).isFalse();
        return token;
    }

    private HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        var response = client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        System.out.printf("CP59_HTTP %s %s status=%d%n", method, path, response.statusCode());
        return response;
    }

    private void securityError(HttpResponse<String> response, int status, String code, String label) throws Exception {
        assertThat(response.statusCode()).as(label).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("error").path("code").asText()).isEqualTo(code);
        assertThat(body.path("data").isNull() || body.path("data").isMissingNode()).isTrue();
    }

    private void setStatus(String status) {
        assertThat(jdbc.sql("UPDATE sys_user SET status = :status WHERE id = :id")
                .param("status", status).param("id", userId).update()).isEqualTo(1);
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE actor_id = :id")
                .param("id", userId).query(Long.class).single();
    }

    @Override
    public void close() {
        // Only rows owned by this newly-created fixture; preserve seed users and existing demo facts.
        jdbc.sql("DELETE FROM incident_timeline WHERE actor_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM audit_log WHERE actor_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM sys_user WHERE id = :id").param("id", userId).update();
    }
}
