package org.trigger.opspilot.postmortem;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-follow-up-notification-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.dashscope.api-key=disabled",
        "opspilot.ai.enabled=false",
        "opspilot.postmortem.follow-up.notification.enabled=true",
        "opspilot.postmortem.follow-up.notification.token=notification-test-token",
        "opspilot.postmortem.follow-up.notification.connect-timeout=500ms",
        "opspilot.postmortem.follow-up.notification.read-timeout=500ms",
        "opspilot.postmortem.follow-up.notification.retry-base-delay=10ms",
        "opspilot.postmortem.follow-up.notification.retry-max-delay=100ms",
        "opspilot.postmortem.follow-up.notification.lease=5s",
        "opspilot.postmortem.follow-up.notification.max-attempts=3",
        "opspilot.postmortem.follow-up.notification.batch-size=20",
        "FOLLOW_UP_NOTIFICATION_DISPATCH_DELAY=600000",
        "FOLLOW_UP_NOTIFICATION_DISPATCH_INITIAL_DELAY=600000"
})
@AutoConfigureMockMvc
class FollowUpNotificationIntegrationTest {
    private static final HttpServer receiver = startReceiver();
    private static final AtomicInteger responseStatus = new AtomicInteger(204);
    private static final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();
    private static final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    private static final List<String> bodies = new CopyOnWriteArrayList<>();

    @Autowired private JdbcClient jdbc;
    @Autowired private FollowUpEscalationService escalations;
    @Autowired private FollowUpNotificationDelivery delivery;
    @Autowired private FollowUpOperationsService operations;
    @Autowired private TransactionTemplate transactions;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void notificationUrl(DynamicPropertyRegistry registry) {
        registry.add("opspilot.postmortem.follow-up.notification.url",
                () -> "http://127.0.0.1:" + receiver.getAddress().getPort() + "/follow-ups");
    }

    @AfterAll
    static void stopReceiver() {
        receiver.stop(0);
    }

    @Test
    void deliversOnceWithStableKeyRetriesTransientFailureAndSkipsResolvedEscalation() throws Exception {
        LocalDate asOf = escalations.businessToday();
        seedPostmortem();
        seedFollowUp(901, asOf.minusDays(2));
        assertThat(escalations.scan(asOf, 3L, "test").createdEscalations()).isEqualTo(1);
        assertThat(escalations.scan(asOf, 3L, "test").createdEscalations()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM postmortem_follow_up_notification")
                .query(Long.class).single()).isEqualTo(1L);
        jdbc.sql("UPDATE postmortem_follow_up SET title = '后续编辑标题', owner_id = 3 WHERE id = 901")
                .update();

        responseStatus.set(503);
        assertThat(delivery.dispatchDue()).isEqualTo(1);
        assertThat(state(901)).isEqualTo("PENDING:1:503");
        assertThat(jdbc.sql("""
                        SELECT last_error_code FROM postmortem_follow_up_notification
                        WHERE escalation_id = :id
                        """).param("id", escalationId(901)).query(String.class).single())
                .isEqualTo("HTTP_503");
        Thread.sleep(30);
        responseStatus.set(204);
        assertThat(delivery.dispatchDue()).isEqualTo(1);
        assertThat(state(901)).isEqualTo("DELIVERED:2:204");
        assertThat(delivery.dispatchDue()).isZero();
        assertThat(operations.list(3, "ALL", "", false, asOf, 1, 20).items())
                .filteredOn(item -> item.id() == 901)
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.notificationStatus()).isEqualTo("DELIVERED");
                    assertThat(item.notificationAttempts()).isEqualTo(2);
                    assertThat(item.notificationHttpStatus()).isEqualTo(204);
                });
        assertThat(idempotencyKeys).containsExactly(
                "follow-up-escalation:" + escalationId(901),
                "follow-up-escalation:" + escalationId(901));
        assertThat(authorizationHeaders).containsExactly(
                "Bearer notification-test-token", "Bearer notification-test-token");
        assertThat(bodies.get(0)).contains("\"eventType\":\"FOLLOW_UP_OVERDUE\"")
                .contains("\"followUpId\":901")
                .contains("\"incidentCode\"")
                .contains("补齐发布验证")
                .doesNotContain("后续编辑标题");

        seedFollowUp(902, asOf.minusDays(1));
        assertThat(escalations.scan(asOf, 3L, "test").createdEscalations()).isEqualTo(1);
        assertThat(escalations.resolve(902, 3L, "test")).isTrue();
        assertThat(delivery.dispatchDue()).isEqualTo(1);
        assertThat(state(902)).startsWith("SKIPPED:1:");
        assertThat(idempotencyKeys).hasSize(2);

        seedFollowUp(903, asOf.minusDays(1));
        assertThat(escalations.scan(asOf, 3L, "test").createdEscalations()).isEqualTo(1);
        responseStatus.set(400);
        assertThat(delivery.dispatchDue()).isEqualTo(1);
        assertThat(state(903)).isEqualTo("FAILED:1:400");
        assertThat(delivery.dispatchDue()).isZero();
        mockMvc.perform(post("/api/v1/postmortem-follow-ups/903/notification/retry")
                        .header("Authorization", "Bearer " + login("zhangwei")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/postmortem-follow-ups/903/notification/retry")
                        .header("Authorization", "Bearer " + login("lina")))
                .andExpect(status().isOk());
        assertThat(state(903)).isEqualTo("PENDING:0:0");
        responseStatus.set(204);
        assertThat(delivery.dispatchDue()).isEqualTo(1);
        assertThat(state(903)).isEqualTo("DELIVERED:1:204");
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM audit_log
                        WHERE action = 'FOLLOW_UP_NOTIFICATION_RETRY'
                          AND target_id = '903'
                        """).query(Long.class).single()).isEqualTo(1L);

        seedFollowUp(904, asOf.minusDays(1));
        assertThat(escalations.scan(asOf, 3L, "test").createdEscalations()).isEqualTo(1);
        int beforeConcurrent = idempotencyKeys.size();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { start.await(); return delivery.dispatchDue(); });
            var second = executor.submit(() -> { start.await(); return delivery.dispatchDue(); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        assertThat(state(904)).isEqualTo("DELIVERED:1:204");
        assertThat(idempotencyKeys).hasSize(beforeConcurrent + 1);

        seedFollowUp(905, asOf.minusDays(1));
        assertThat(escalations.scan(asOf, 3L, "test").createdEscalations()).isEqualTo(1);
        responseStatus.set(503);
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (attempt > 1) Thread.sleep(60);
            assertThat(delivery.dispatchDue()).isEqualTo(1);
        }
        assertThat(state(905)).isEqualTo("FAILED:3:503");
        assertThat(delivery.dispatchDue()).isZero();

        seedFollowUp(906, asOf.minusDays(1));
        assertThat(escalations.scan(asOf, 3L, "test").createdEscalations()).isEqualTo(1);
        responseStatus.set(429);
        assertThat(delivery.dispatchDue()).isEqualTo(1);
        assertThat(state(906)).isEqualTo("PENDING:1:429");
        Thread.sleep(30);
        responseStatus.set(204);
        assertThat(delivery.dispatchDue()).isEqualTo(1);
        assertThat(state(906)).isEqualTo("DELIVERED:2:204");

        seedFollowUp(907, asOf.minusDays(1));
        assertThat(escalations.scan(asOf, 3L, "test").createdEscalations()).isEqualTo(1);
        responseStatus.set(302);
        assertThat(delivery.dispatchDue()).isEqualTo(1);
        assertThat(state(907)).isEqualTo("FAILED:1:302");

        seedFollowUp(908, asOf.minusDays(1));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            escalations.scan(asOf, 3L, "test");
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("""
                        SELECT COUNT(*) FROM postmortem_follow_up_notification notification
                        JOIN postmortem_follow_up_escalation escalation
                          ON escalation.id = notification.escalation_id
                        WHERE escalation.follow_up_id = 908
                        """).query(Long.class).single()).isZero();
    }

    private void seedPostmortem() {
        jdbc.sql("""
                        INSERT INTO incident_postmortem(
                          id, incident_id, status, summary, customer_impact, root_cause,
                          contributing_factors, lessons_learned, timeline_snapshot_json,
                          evidence_refs_json, created_by, published_at)
                        VALUES (801, 2, 'PUBLISHED', '摘要', '影响', '根因', '因素', '经验',
                                '[]', '[]', 3, CURRENT_TIMESTAMP)
                        """).update();
    }

    private void seedFollowUp(long id, LocalDate dueDate) {
        jdbc.sql("""
                        INSERT INTO postmortem_follow_up(
                          id, postmortem_id, title, description, priority, status,
                          owner_id, due_date, created_by)
                        VALUES (:id, 801, '补齐发布验证', '接入自动化门禁', 'HIGH', 'OPEN',
                                2, :dueDate, 3)
                        """).param("id", id).param("dueDate", dueDate).update();
    }

    private long escalationId(long followUpId) {
        return jdbc.sql("SELECT id FROM postmortem_follow_up_escalation WHERE follow_up_id = :id")
                .param("id", followUpId).query(Long.class).single();
    }

    private String state(long followUpId) {
        return jdbc.sql("""
                        SELECT CONCAT(notification.status, ':', notification.attempts, ':',
                                      COALESCE(notification.last_http_status, 0))
                        FROM postmortem_follow_up_notification notification
                        JOIN postmortem_follow_up_escalation escalation
                          ON escalation.id = notification.escalation_id
                        WHERE escalation.follow_up_id = :id
                        """).param("id", followUpId).query(String.class).single();
    }

    private static HttpServer startReceiver() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/follow-ups", exchange -> {
                idempotencyKeys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                int status = responseStatus.get();
                if (status == 204) {
                    exchange.sendResponseHeaders(status, -1);
                } else {
                    byte[] body = "remote-secret-sentinel".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String login(String username) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", "OpsPilot@2026"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("accessToken").asText();
    }
}
