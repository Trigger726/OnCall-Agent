package org.trigger.opspilot.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-alertmanager-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "opspilot.alertmanager.webhook.secret=webhook-test-secret",
        "opspilot.alertmanager.webhook.max-alerts=2",
        "opspilot.agent.recovery.enabled=false"
})
@AutoConfigureMockMvc
class AlertmanagerWebhookIntegrationTest {
    private static final String ENDPOINT = "/api/v1/integrations/alertmanager/webhook";
    private static final String AUTHORIZATION = "OpsPilot webhook-test-secret";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbcClient;
    @Autowired private AlertmanagerRejectionService rejectionService;

    @Test
    void shouldRequireDedicatedWebhookCredentialAndBoundBatchSize() throws Exception {
        mockMvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                        .content(webhook(alert("firing", "am-auth", "APP-PORTAL", "warning",
                                "PortalAuthTest", "2026-09-23T01:00:00Z", "2026-09-23T02:00:00Z"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ALERTMANAGER_WEBHOOK_UNAUTHORIZED"));

        mockMvc.perform(post(ENDPOINT).header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_JSON"));

        String threeAlerts = webhook(
                alert("firing", "am-limit-1", "APP-PORTAL", "info", "LimitOne",
                        "2026-09-23T01:00:00Z", "2026-09-23T02:00:00Z"),
                alert("firing", "am-limit-2", "APP-PORTAL", "info", "LimitTwo",
                        "2026-09-23T01:00:00Z", "2026-09-23T02:00:00Z"),
                alert("firing", "am-limit-3", "APP-PORTAL", "info", "LimitThree",
                        "2026-09-23T01:00:00Z", "2026-09-23T02:00:00Z"));
        mockMvc.perform(post(ENDPOINT).header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON).content(threeAlerts))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("ALERTMANAGER_BATCH_TOO_LARGE"));

        String unmappedSeverity = webhook(alert("firing", "am-unmapped", "APP-PORTAL", "page",
                "UnmappedSeverity", "2026-09-23T01:00:00Z", "2026-09-23T02:00:00Z"));
        mockMvc.perform(post(ENDPOINT).header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON).content(unmappedSeverity))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(0))
                .andExpect(jsonPath("$.data.rejected").value(1))
                .andExpect(jsonPath("$.data.items[0].errorCode").value("ALERTMANAGER_SEVERITY_UNMAPPED"));
    }

    @Test
    void shouldAcceptValidAlertAndIsolatePermanentBadItem() throws Exception {
        String payload = webhook(
                alert("firing", "am-partial-good", "APP-PORTAL", "critical", "PortalHighError",
                        "2026-09-23T02:00:00Z", "2026-09-23T03:00:00Z"),
                alert("firing", "am-partial-bad", "APP-MISSING", "warning", "MissingResource",
                        "2026-09-23T02:00:00Z", "2026-09-23T03:00:00Z"));

        String response = mockMvc.perform(post(ENDPOINT).header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.received").value(2))
                .andExpect(jsonPath("$.data.accepted").value(1))
                .andExpect(jsonPath("$.data.rejected").value(1))
                .andExpect(jsonPath("$.data.items[0].action").value("CREATED"))
                .andExpect(jsonPath("$.data.items[1].action").value("REJECTED"))
                .andExpect(jsonPath("$.data.items[1].errorCode").value("RESOURCE_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();

        long incidentId = objectMapper.readTree(response).path("data").path("items").get(0)
                .path("incidentId").asLong();
        JsonNode stored = queryAlert("am-partial-good:1790128800000");
        assertThat(stored.path("severity").asText()).isEqualTo("P1");
        assertThat(stored.path("status").asText()).isEqualTo("FIRING");
        assertThat(stored.path("occurrenceCount").asInt()).isEqualTo(1);
        assertThat(stored.path("title").asText()).isEqualTo("PortalHighError");
        assertThat(incidentId).isPositive();
        assertThat(countAlert("am-partial-bad:1790128800000")).isZero();
    }

    @Test
    void shouldMakeRetryIdempotentAndRecordResolvedTransitionOnce() throws Exception {
        String firing = webhook(alert("firing", "am-lifecycle", "APP-AUTH", "info", "AuthLatencyHigh",
                "2026-09-23T04:00:00Z", "2026-09-23T05:00:00Z"));

        String first = postWebhook(firing, "CREATED");
        long alertId = objectMapper.readTree(first).path("data").path("items").get(0).path("alertId").asLong();
        long incidentId = objectMapper.readTree(first).path("data").path("items").get(0).path("incidentId").asLong();
        postWebhook(firing, "REPLAYED");

        String resolved = webhook(alert("resolved", "am-lifecycle", "APP-AUTH", "info", "AuthLatencyHigh",
                "2026-09-23T04:00:00Z", "2026-09-23T04:20:00Z"));
        postWebhook(resolved, "UPDATED");
        postWebhook(resolved, "REPLAYED");

        MapRow stored = jdbcClient.sql("""
                        SELECT status, occurrence_count, version, last_occurred_at
                        FROM alert_event WHERE id = :id
                        """).param("id", alertId)
                .query((rs, rowNum) -> new MapRow(rs.getString("status"), rs.getInt("occurrence_count"),
                        rs.getInt("version"), rs.getObject("last_occurred_at", java.time.LocalDateTime.class)))
                .single();
        assertThat(stored.status()).isEqualTo("RESOLVED");
        assertThat(stored.occurrenceCount()).isEqualTo(1);
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.lastOccurredAt()).isEqualTo(java.time.LocalDateTime.of(2026, 9, 23, 4, 20));
        Integer resolvedTimelineCount = jdbcClient.sql("""
                        SELECT COUNT(*) FROM incident_timeline
                        WHERE incident_id = :incidentId AND event_type = 'ALERT_RESOLVED'
                          AND evidence_ref = :evidenceRef
                        """).param("incidentId", incidentId).param("evidenceRef", "alert:" + alertId)
                .query(Integer.class).single();
        assertThat(resolvedTimelineCount).isEqualTo(1);
    }

    @Test
    void shouldPersistDeduplicateAndRedactRejectedDeliveryWithReadOnlyAuditAccess() throws Exception {
        String rejected = """
                {
                  "version": "4",
                  "groupKey": "rejection-group",
                  "receiver": "opspilot",
                  "alerts": [{
                    "status": "firing",
                    "labels": {
                      "alertname": "RejectedSensitiveAlert",
                      "resource_code": "APP-REJECT-MISSING",
                      "severity": "warning",
                      "api_token": "plain-secret-value"
                    },
                    "annotations": {"description": "authorization=Bearer sensitive-token"},
                    "startsAt": "2026-09-23T06:00:00Z",
                    "endsAt": "2026-09-23T07:00:00Z",
                    "fingerprint": "am-rejection-ledger"
                  }]
                }
                """;

        long firstId = rejectionId(postRejectedWebhook(rejected));
        AlertmanagerRejectionService.ReplayClaim claim = rejectionService.claim(firstId);
        String updatedEndsAt = rejected.replace("2026-09-23T07:00:00Z", "2026-09-23T07:05:00Z");
        long secondId = rejectionId(postRejectedWebhook(updatedEndsAt));
        assertThat(secondId).isEqualTo(firstId);
        assertThatThrownBy(() -> rejectionService.claim(firstId))
                .isInstanceOfSatisfying(org.trigger.opspilot.common.ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ALERTMANAGER_REPLAY_IN_PROGRESS"));
        rejectionService.release(firstId, claim.token(), "TEST_RELEASE", "test lease released");

        String payload = jdbcClient.sql("SELECT payload_json FROM alert_ingest_rejection WHERE id = :id")
                .param("id", firstId).query(String.class).single();
        assertThat(payload).doesNotContain("plain-secret-value", "sensitive-token", "2026-09-23T07:05:00Z")
                .contains("***", "2026-09-23T07:00:00Z");

        String auditor = login("auditor");
        String listing = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/v1/integrations/alertmanager/rejections")
                        .header("Authorization", "Bearer " + auditor).param("status", "OPEN"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(listing).doesNotContain("plain-secret-value", "sensitive-token", "payloadJson");
        JsonNode listed = objectMapper.readTree(listing).path("data").path("items");
        JsonNode item = null;
        for (JsonNode candidate : listed) {
            if (candidate.path("id").asLong() == firstId) item = candidate;
        }
        assertThat(item).isNotNull();
        assertThat(item.path("deliveryCount").asInt()).isEqualTo(2);
        assertThat(item.path("redactedFields").asInt()).isEqualTo(2);
        mockMvc.perform(post("/api/v1/integrations/alertmanager/rejections/{id}/replay", firstId)
                        .header("Authorization", "Bearer " + auditor))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    void shouldReplayOnceAfterCmdbFixAcrossConcurrentRequests() throws Exception {
        String rejected = webhook(alert("firing", "am-replay-ledger", "APP-LATE-REPLAY", "warning",
                "LateResourceAlert", "2026-09-23T08:00:00Z", "2026-09-23T09:00:00Z"));
        long rejectionId = rejectionId(postRejectedWebhook(rejected));

        jdbcClient.sql("""
                        INSERT INTO cmdb_resource(resource_code, resource_type, name, environment, status,
                          owner_user_id, description, attributes_json)
                        VALUES ('APP-LATE-REPLAY', 'APPLICATION', '延迟登记服务', 'PRODUCTION', 'RUNNING',
                          2, '用于验证拒绝项重放', '{}')
                        """).update();
        String manager = login("lina");
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var calls = List.of(1, 2).stream().map(ignored -> executor.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                var response = mockMvc.perform(post(
                                "/api/v1/integrations/alertmanager/rejections/{id}/replay", rejectionId)
                                .header("Authorization", "Bearer " + manager))
                        .andReturn().getResponse();
                return new HttpResult(response.getStatus(), response.getContentAsString());
            })).toList();
            start.countDown();
            List<HttpResult> responses = calls.stream().map(future -> {
                try {
                    return future.get(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
            assertThat(responses).allMatch(response -> response.status() == 200 || response.status() == 409);
            assertThat(responses).anyMatch(response -> response.status() == 200
                    && response.body().contains("CREATED"));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        mockMvc.perform(post("/api/v1/integrations/alertmanager/rejections/{id}/replay", rejectionId)
                        .header("Authorization", "Bearer " + manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.action").value("ALREADY_SUCCEEDED"));
        assertThat(countAlert("am-replay-ledger:1790150400000")).isEqualTo(1);
        LedgerRow ledger = jdbcClient.sql("""
                        SELECT status, delivery_count, replay_count, resolved_alert_id, resolved_incident_id
                        FROM alert_ingest_rejection WHERE id = :id
                        """).param("id", rejectionId)
                .query((rs, rowNum) -> new LedgerRow(rs.getString("status"), rs.getInt("delivery_count"),
                        rs.getInt("replay_count"), rs.getLong("resolved_alert_id"),
                        rs.getLong("resolved_incident_id"))).single();
        assertThat(ledger.status()).isEqualTo("SUCCEEDED");
        assertThat(ledger.deliveryCount()).isEqualTo(1);
        assertThat(ledger.replayCount()).isEqualTo(1);
        assertThat(ledger.alertId()).isPositive();
        assertThat(ledger.incidentId()).isPositive();
        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM audit_log
                        WHERE action = 'ALERTMANAGER_REJECTION_REPLAYED' AND target_id = :targetId
                        """).param("targetId", Long.toString(rejectionId)).query(Integer.class).single()).isEqualTo(1);
    }

    private String postWebhook(String payload, String action) throws Exception {
        return mockMvc.perform(post(ENDPOINT).header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(1))
                .andExpect(jsonPath("$.data.items[0].action").value(action))
                .andReturn().getResponse().getContentAsString();
    }

    private String postRejectedWebhook(String payload) throws Exception {
        return mockMvc.perform(post(ENDPOINT).header("Authorization", AUTHORIZATION)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(0))
                .andExpect(jsonPath("$.data.rejected").value(1))
                .andExpect(jsonPath("$.data.items[0].action").value("REJECTED"))
                .andExpect(jsonPath("$.data.items[0].rejectionId").isNumber())
                .andReturn().getResponse().getContentAsString();
    }

    private long rejectionId(String response) throws Exception {
        return objectMapper.readTree(response).path("data").path("items").get(0).path("rejectionId").asLong();
    }

    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", "OpsPilot@2026"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("accessToken").asText();
    }

    private JsonNode queryAlert(String externalEventId) throws Exception {
        String json = jdbcClient.sql("""
                        SELECT severity, status, occurrence_count, title FROM alert_event
                        WHERE source = 'alertmanager' AND external_event_id = :externalEventId
                        """).param("externalEventId", externalEventId)
                .query((rs, rowNum) -> "{\"severity\":\"" + rs.getString("severity")
                        + "\",\"status\":\"" + rs.getString("status")
                        + "\",\"occurrenceCount\":" + rs.getInt("occurrence_count")
                        + ",\"title\":\"" + rs.getString("title") + "\"}")
                .single();
        return objectMapper.readTree(json);
    }

    private int countAlert(String externalEventId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM alert_event
                        WHERE source = 'alertmanager' AND external_event_id = :externalEventId
                        """).param("externalEventId", externalEventId).query(Integer.class).single();
    }

    private static String webhook(String... alerts) {
        return """
                {
                  "version": "4",
                  "groupKey": "opspilot-test-group",
                  "status": "firing",
                  "receiver": "opspilot",
                  "commonAnnotations": {"summary": "group summary"},
                  "alerts": [%s]
                }
                """.formatted(String.join(",", alerts));
    }

    private static String alert(String status, String fingerprint, String resourceCode, String severity,
                                String alertName, String startsAt, String endsAt) {
        return """
                {
                  "status": "%s",
                  "labels": {
                    "alertname": "%s",
                    "resource_code": "%s",
                    "severity": "%s"
                  },
                  "annotations": {"summary": "%s summary", "description": "%s description"},
                  "startsAt": "%s",
                  "endsAt": "%s",
                  "generatorURL": "http://prometheus.example/graph",
                  "fingerprint": "%s"
                }
                """.formatted(status, alertName, resourceCode, severity, alertName, alertName,
                startsAt, endsAt, fingerprint);
    }

    private record MapRow(String status, int occurrenceCount, int version,
                          java.time.LocalDateTime lastOccurredAt) {
    }

    private record LedgerRow(String status, int deliveryCount, int replayCount, long alertId, long incidentId) {
    }

    private record HttpResult(int status, String body) {
    }
}
