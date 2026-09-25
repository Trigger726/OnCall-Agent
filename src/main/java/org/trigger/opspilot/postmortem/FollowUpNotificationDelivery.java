package org.trigger.opspilot.postmortem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FollowUpNotificationDelivery {
    private static final Logger log = LoggerFactory.getLogger(FollowUpNotificationDelivery.class);

    private final JdbcClient jdbc;
    private final AuditService auditService;
    private final FollowUpNotificationProperties properties;
    private final RestClient client;
    private final URI destination;

    public FollowUpNotificationDelivery(JdbcClient jdbc, AuditService auditService,
                                        FollowUpNotificationProperties properties, RestClient.Builder builder) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.properties = properties;
        if (!properties.enabled()) {
            this.client = null;
            this.destination = null;
            return;
        }
        if (properties.url() == null || properties.url().isBlank()) {
            throw new IllegalArgumentException("Follow-up notification URL is required when enabled");
        }
        destination = URI.create(properties.url());
        boolean secure = "https".equalsIgnoreCase(destination.getScheme());
        boolean local = "http".equalsIgnoreCase(destination.getScheme())
                && ("localhost".equalsIgnoreCase(destination.getHost())
                || "127.0.0.1".equals(destination.getHost()));
        if ((!secure && !local) || destination.getHost() == null
                || destination.getUserInfo() != null
                || destination.getFragment() != null || properties.token() == null
                || properties.token().isBlank() || properties.maxAttempts() < 1
                || properties.maxAttempts() > 10 || properties.batchSize() < 1
                || properties.batchSize() > 100 || !positive(properties.connectTimeout())
                || !positive(properties.readTimeout()) || !positive(properties.lease())
                || !positive(properties.retryBaseDelay()) || !positive(properties.retryMaxDelay())
                || properties.retryBaseDelay().compareTo(properties.retryMaxDelay()) > 0
                || properties.connectTimeout().toMillis() < 1
                || properties.readTimeout().toMillis() < 1
                || properties.lease().compareTo(
                        properties.connectTimeout().plus(properties.readTimeout())) <= 0) {
            throw new IllegalArgumentException("Invalid follow-up notification configuration");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.toIntExact(properties.connectTimeout().toMillis()));
        requestFactory.setReadTimeout(Math.toIntExact(properties.readTimeout().toMillis()));
        client = builder.requestFactory(requestFactory).build();
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }

    @Scheduled(fixedDelayString = "${FOLLOW_UP_NOTIFICATION_DISPATCH_DELAY:5000}",
            initialDelayString = "${FOLLOW_UP_NOTIFICATION_DISPATCH_INITIAL_DELAY:5000}")
    public int dispatchDue() {
        if (!properties.enabled()) return 0;
        LocalDateTime now = utcNow();
        List<Long> ids = jdbc.sql("""
                        SELECT id FROM postmortem_follow_up_notification
                        WHERE (status = 'PENDING' AND next_attempt_at <= :now)
                           OR (status = 'CLAIMED' AND lease_until <= :now)
                        ORDER BY id LIMIT :limit
                        """).param("now", now).param("limit", properties.batchSize())
                .query(Long.class).list();
        List<Claim> claims = new ArrayList<>();
        for (long id : ids) {
            String token = UUID.randomUUID().toString();
            int updated = jdbc.sql("""
                            UPDATE postmortem_follow_up_notification
                            SET status = 'CLAIMED', lease_token = :token, lease_until = :until,
                                attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP
                            WHERE id = :id AND
                              ((status = 'PENDING' AND next_attempt_at <= :now)
                               OR (status = 'CLAIMED' AND lease_until <= :now))
                            """).param("id", id).param("token", token)
                    .param("until", now.plus(properties.lease())).param("now", now).update();
            if (updated == 1) claims.add(new Claim(id, token));
        }
        for (Claim claim : claims) deliver(claim);
        return claims.size();
    }

    @Transactional
    public void retryFailed(long followUpId, long actorId, String sourceIp) {
        if (!properties.enabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "FOLLOW_UP_NOTIFICATION_DISABLED",
                    "外部提醒未启用");
        }
        int updated = jdbc.sql("""
                        UPDATE postmortem_follow_up_notification
                        SET status = 'PENDING', attempts = 0, next_attempt_at = :now,
                            last_http_status = NULL, last_error_code = NULL,
                            lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                        WHERE status = 'FAILED' AND escalation_id IN (
                          SELECT escalation.id FROM postmortem_follow_up_escalation escalation
                          JOIN postmortem_follow_up follow_up
                            ON follow_up.id = escalation.follow_up_id
                          JOIN incident_postmortem postmortem
                            ON postmortem.id = follow_up.postmortem_id
                          WHERE follow_up.id = :followUpId AND follow_up.status = 'OPEN'
                            AND escalation.status = 'OPEN' AND postmortem.status = 'PUBLISHED')
                        """).param("now", utcNow()).param("followUpId", followUpId).update();
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "FOLLOW_UP_NOTIFICATION_NOT_RETRYABLE",
                    "只有仍逾期且通知失败的行动项可重试");
        }
        auditService.recordAs(actorId, sourceIp, "FOLLOW_UP_NOTIFICATION_RETRY",
                "POSTMORTEM_FOLLOW_UP", followUpId, "人工重试外部逾期提醒");
    }

    private void deliver(Claim claim) {
        Notification notification = jdbc.sql("""
                        SELECT delivery.attempts, escalation.id AS escalation_id,
                               escalation.status AS escalation_status,
                               escalation.due_date_snapshot, escalation.follow_up_id,
                               follow_up.status AS follow_up_status,
                               postmortem.status AS postmortem_status,
                               delivery.title_snapshot, delivery.owner_name_snapshot,
                               delivery.incident_code_snapshot
                        FROM postmortem_follow_up_notification delivery
                        JOIN postmortem_follow_up_escalation escalation
                          ON escalation.id = delivery.escalation_id
                        JOIN postmortem_follow_up follow_up
                          ON follow_up.id = escalation.follow_up_id
                        JOIN incident_postmortem postmortem
                          ON postmortem.id = follow_up.postmortem_id
                        WHERE delivery.id = :id
                        """).param("id", claim.id())
                .query((rs, rowNum) -> new Notification(
                        rs.getInt("attempts"), rs.getLong("escalation_id"),
                        rs.getString("escalation_status"),
                        rs.getObject("due_date_snapshot", LocalDate.class),
                        rs.getString("follow_up_status"), rs.getString("postmortem_status"),
                        rs.getLong("follow_up_id"), rs.getString("title_snapshot"),
                        rs.getString("owner_name_snapshot"),
                        rs.getString("incident_code_snapshot"))).single();
        if (!"OPEN".equals(notification.escalationStatus())) {
            finish(claim, "SKIPPED", null, "ESCALATION_RESOLVED");
            return;
        }
        if (!"OPEN".equals(notification.followUpStatus())) {
            finish(claim, "SKIPPED", null, "FOLLOW_UP_COMPLETED");
            return;
        }
        if (!"PUBLISHED".equals(notification.postmortemStatus())) {
            finish(claim, "FAILED", null, "POSTMORTEM_NOT_PUBLISHED");
            return;
        }
        Integer httpStatus = null;
        String errorCode = null;
        try {
            Map<String, Object> payload = Map.of(
                    "eventType", "FOLLOW_UP_OVERDUE",
                    "escalationId", notification.escalationId(),
                    "followUpId", notification.followUpId(),
                    "incidentCode", notification.incidentCode(),
                    "title", notification.title(),
                    "ownerName", notification.ownerName(),
                    "dueDate", notification.dueDate().toString());
            httpStatus = client.post().uri(destination)
                    .header("Authorization", "Bearer " + properties.token())
                    .header("Idempotency-Key", "follow-up-escalation:" + notification.escalationId())
                    .body(payload).exchange((request, response) -> response.getStatusCode().value());
        } catch (RuntimeException exception) {
            errorCode = "TRANSPORT_ERROR";
        }
        if (httpStatus != null && httpStatus >= 200 && httpStatus < 300) {
            finish(claim, "DELIVERED", httpStatus, null);
        } else if (httpStatus != null && httpStatus >= 300 && httpStatus < 500
                && httpStatus != 429) {
            finish(claim, "FAILED", httpStatus, "HTTP_" + httpStatus);
        } else if (notification.attempts() >= properties.maxAttempts()) {
            finish(claim, "FAILED", httpStatus,
                    errorCode == null ? "HTTP_" + httpStatus : errorCode);
        } else {
            retry(claim, notification.attempts(), httpStatus,
                    errorCode == null ? "HTTP_" + httpStatus : errorCode);
        }
    }

    private void finish(Claim claim, String status, Integer httpStatus, String errorCode) {
        int updated = jdbc.sql("""
                        UPDATE postmortem_follow_up_notification
                        SET status = :status, last_http_status = :httpStatus,
                            last_error_code = :errorCode, lease_token = NULL, lease_until = NULL,
                            delivered_at = CASE WHEN :status = 'DELIVERED' THEN :now ELSE delivered_at END,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND status = 'CLAIMED' AND lease_token = :token
                          AND lease_until > :now
                        """).param("status", status).param("httpStatus", httpStatus)
                .param("errorCode", errorCode).param("now", utcNow())
                .param("id", claim.id()).param("token", claim.token()).update();
        if (updated == 0) log.warn("Follow-up notification lease expired before receipt: {}", claim.id());
    }

    private void retry(Claim claim, int attempts, Integer httpStatus, String errorCode) {
        Duration delay = properties.retryBaseDelay();
        for (int i = 1; i < attempts && delay.compareTo(properties.retryMaxDelay()) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        if (delay.compareTo(properties.retryMaxDelay()) > 0) delay = properties.retryMaxDelay();
        LocalDateTime now = utcNow();
        int updated = jdbc.sql("""
                        UPDATE postmortem_follow_up_notification
                        SET status = 'PENDING', next_attempt_at = :next,
                            last_http_status = :httpStatus, last_error_code = :errorCode,
                            lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                        WHERE id = :id AND status = 'CLAIMED' AND lease_token = :token
                          AND lease_until > :now
                        """).param("next", now.plus(delay)).param("httpStatus", httpStatus)
                .param("errorCode", errorCode).param("id", claim.id())
                .param("token", claim.token()).param("now", now).update();
        if (updated == 0) log.warn("Follow-up notification lease expired before retry: {}", claim.id());
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private record Claim(long id, String token) { }

    private record Notification(int attempts, long escalationId, String escalationStatus,
                                LocalDate dueDate, String followUpStatus, String postmortemStatus,
                                long followUpId, String title,
                                String ownerName, String incidentCode) { }
}
