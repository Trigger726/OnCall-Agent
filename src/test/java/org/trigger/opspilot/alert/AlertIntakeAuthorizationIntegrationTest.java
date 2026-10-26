package org.trigger.opspilot.alert;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.security.JwtProperties;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:intake-auth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=disabled", "opspilot.ai.enabled=false",
        "opspilot.oncall.rotation.enabled=false", "opspilot.oncall.escalation.enabled=false"
})
class AlertIntakeAuthorizationIntegrationTest extends AlertIntakeAuthorizationScenarios { }

// Intentionally no outer test transaction: assert endpoint commits and actual rollback, on both databases.
@AutoConfigureMockMvc
abstract class AlertIntakeAuthorizationScenarios {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired JwtProperties jwtProperties;
    @SpyBean AuditService audit;
    List<Map<String, Object>> accounts;
    String source, externalId;

    @BeforeEach void snapshotAccounts() {
        accounts = jdbc.sql("SELECT id,role_code,status,auth_version FROM sys_user WHERE username IN ('admin','lina','zhangwei','auditor')")
                .query().listOfRows();
        source = "auth-it-" + UUID.randomUUID();
        externalId = UUID.randomUUID().toString();
    }

    @AfterEach void restoreAccounts() {
        for (var row : accounts) {
            jdbc.sql("UPDATE sys_user SET role_code=:role,status=:status,auth_version=:version WHERE id=:id")
                    .param("role", row.get("role_code")).param("status", row.get("status"))
                    .param("version", row.get("auth_version")).param("id", row.get("id")).update();
        }
    }

    String login(String username) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", "OpsPilot@2026"))))
                .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).path("data").path("accessToken").asText();
    }

    String payload(String state) throws Exception {
        return json.writeValueAsString(Map.of("source", source, "externalEventId", externalId,
                "resourceCode", "APP-AUTH", "severity", "P3", "status", state,
                "title", "intake-" + externalId, "description", "private-body-sentinel",
                "labels", Map.of("private", "private-label-sentinel")));
    }

    MockHttpServletRequestBuilder request(String token, String state) throws Exception {
        var request = post("/api/v1/alerts/intake").contentType(MediaType.APPLICATION_JSON).content(payload(state));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request;
    }

    Map<String, Long> counts() {
        var result = new LinkedHashMap<String, Long>();
        for (var table : List.of("alert_event", "incident", "incident_timeline", "audit_log")) {
            result.put(table, jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single());
        }
        return result;
    }

    JsonNode accept(String token, String state) throws Exception {
        return json.readTree(mvc.perform(request(token, state)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
    }

    @Test void anonymousInvalidAndMalformedAnonymousRequestsCannotWrite() throws Exception {
        var before = counts();
        mvc.perform(request(null, "FIRING")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(request("invalid-token", "FIRING")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/alerts/intake").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isUnauthorized());
        assertThat(counts()).isEqualTo(before);
    }

    @Test void auditorCannotWrite() throws Exception {
        var token = login("auditor");
        var before = counts();
        mvc.perform(request(token, "FIRING")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        assertThat(counts()).isEqualTo(before);
    }

    @Test void everyWriterRoleHasBoundedActorAudit() throws Exception {
        for (var username : List.of("admin", "lina", "zhangwei")) {
            var token = login(username);
            externalId = UUID.randomUUID().toString();
            var result = accept(token, "FIRING");
            assertThat(result.path("action").asText()).isEqualTo("CREATED");
            var row = jdbc.sql("SELECT actor_id,target_type,detail FROM audit_log WHERE action='ALERT_INTAKE' AND target_id=:id")
                    .param("id", result.path("alertId").asText()).query().singleRow();
            var actor = jdbc.sql("SELECT id FROM sys_user WHERE username=:name").param("name", username).query(Long.class).single();
            assertThat(((Number) row.get("actor_id")).longValue()).isEqualTo(actor);
            assertThat(row.get("target_type")).isEqualTo("ALERT_EVENT");
            assertThat(row.get("detail").toString()).contains("action=CREATED").hasSizeLessThan(150)
                    .doesNotContain(token, source, "private-body-sentinel", "private-label-sentinel");
        }
    }

    @Test void roleDowngradeTakesEffectForAlreadyIssuedToken() throws Exception {
        var token = login("zhangwei");
        jdbc.sql("UPDATE sys_user SET role_code='AUDITOR' WHERE username='zhangwei'").update();
        var before = counts();
        mvc.perform(request(token, "FIRING")).andExpect(status().isForbidden());
        assertThat(counts()).isEqualTo(before);
    }

    @Test void revokedAndDisabledAccountsCannotUseOldTokens() throws Exception {
        var token = login("zhangwei");
        jdbc.sql("UPDATE sys_user SET auth_version=auth_version+1 WHERE username='zhangwei'").update();
        var before = counts();
        mvc.perform(request(token, "FIRING")).andExpect(status().isUnauthorized());
        assertThat(counts()).isEqualTo(before);
        token = login("lina");
        jdbc.sql("UPDATE sys_user SET status='DISABLED' WHERE username='lina'").update();
        before = counts();
        mvc.perform(request(token, "FIRING")).andExpect(status().isUnauthorized());
        assertThat(counts()).isEqualTo(before);
    }

    @Test void signedExpiredTokenCannotWrite() throws Exception {
        var row = jdbc.sql("SELECT id,auth_version FROM sys_user WHERE username='zhangwei'").query().singleRow();
        var token = JWT.create().withIssuer("opspilot").withSubject("zhangwei")
                .withClaim("uid", ((Number) row.get("id")).longValue())
                .withClaim("sv", ((Number) row.get("auth_version")).longValue())
                .withIssuedAt(Instant.now().minusSeconds(120)).withExpiresAt(Instant.now().minusSeconds(60))
                .sign(Algorithm.HMAC256(jwtProperties.jwtSecret()));
        var before = counts();
        mvc.perform(request(token, "FIRING")).andExpect(status().isUnauthorized());
        assertThat(counts()).isEqualTo(before);
    }

    @Test void unauthorizedUpdateCannotChangeEventAndAuthorizedReplayPreservesBusinessRows() throws Exception {
        var token = login("zhangwei");
        var created = accept(token, "FIRING");
        var before = counts();
        mvc.perform(request(null, "RESOLVED")).andExpect(status().isUnauthorized());
        assertThat(counts()).isEqualTo(before);
        var replay = accept(token, "FIRING");
        assertThat(replay.path("action").asText()).isEqualTo("REPLAYED");
        assertThat(replay.path("alertId")).isEqualTo(created.path("alertId"));
        assertThat(replay.path("incidentId")).isEqualTo(created.path("incidentId"));
        var after = counts();
        for (var table : List.of("alert_event", "incident", "incident_timeline")) {
            assertThat(after.get(table)).isEqualTo(before.get(table));
        }
        assertThat(after.get("audit_log")).isEqualTo(before.get("audit_log") + 1);
        var event = jdbc.sql("SELECT status,occurrence_count FROM alert_event WHERE id=:id")
                .param("id", created.path("alertId").asLong()).query().singleRow();
        assertThat(event.get("status")).isEqualTo("FIRING");
        assertThat(((Number) event.get("occurrence_count")).intValue()).isEqualTo(1);
    }

    @Test void auditFailureRollsBackAlertIncidentAndTimeline() throws Exception {
        var token = login("zhangwei");
        var before = counts();
        doThrow(new IllegalStateException("Injected audit failure"))
                .when(audit).record(eq("ALERT_INTAKE"), eq("ALERT_EVENT"), any(), anyString());
        mvc.perform(request(token, "FIRING")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        assertThat(counts()).isEqualTo(before);
    }

    @Test void invalidBodyAndUnknownResourceCannotWrite() throws Exception {
        var token = login("zhangwei");
        var before = counts();
        mvc.perform(post("/api/v1/alerts/intake").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/alerts/intake").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(payload("FIRING").replace("APP-AUTH", "MISSING-AUTH-IT")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        assertThat(counts()).isEqualTo(before);
    }
}
