package org.trigger.opspilot.alert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.common.PageResponse;
import org.trigger.opspilot.observability.LogRedactor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class AlertmanagerRejectionService {
    private static final String SOURCE = "alertmanager";

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final LogRedactor redactor;
    private final AlertmanagerRejectionProperties properties;
    private final AuditService auditService;

    public AlertmanagerRejectionService(JdbcClient jdbcClient, ObjectMapper objectMapper, LogRedactor redactor,
                                        AlertmanagerRejectionProperties properties, AuditService auditService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.redactor = redactor;
        this.properties = properties;
        this.auditService = auditService;
    }

    @Transactional
    public long record(AlertmanagerWebhookService.Webhook webhook, AlertmanagerWebhookService.Alert alert,
                       ApiException failure) {
        String rejectionKey = rejectionKey(alert);
        SanitizedPayload sanitized = sanitize(webhook, alert);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextAutoReplayAt = "RESOURCE_NOT_FOUND".equals(failure.code())
                ? now.plus(properties.getRetryBaseDelay()) : null;
        String payloadJson = json(sanitized.payload());
        int updated = updateExisting(rejectionKey, sanitized, failure, payloadJson, now, nextAutoReplayAt);
        if (updated == 0) {
            try {
                jdbcClient.sql("""
                                INSERT INTO alert_ingest_rejection(
                                  source, rejection_key, external_event_id, receiver, delivery_group_key,
                                  error_code, error_message, payload_json, alert_name, resource_code,
                                  severity, alert_status, redacted_fields, first_received_at,
                                  last_received_at, next_auto_replay_at, updated_at)
                                VALUES (:source, :rejectionKey, :externalEventId, :receiver, :groupKey,
                                  :errorCode, :errorMessage, :payload, :alertName, :resourceCode,
                                  :severity, :alertStatus, :redactedFields, :now, :now, :nextAutoReplayAt, :now)
                                """)
                        .param("source", SOURCE).param("rejectionKey", rejectionKey)
                        .param("externalEventId", sanitized.externalEventId())
                        .param("receiver", sanitized.payload().receiver())
                        .param("groupKey", sanitized.payload().groupKey())
                        .param("errorCode", failure.code()).param("errorMessage", failureMessage(failure))
                        .param("payload", payloadJson)
                        .param("alertName", sanitized.alertName()).param("resourceCode", sanitized.resourceCode())
                        .param("severity", sanitized.severity()).param("alertStatus", sanitized.alertStatus())
                        .param("redactedFields", sanitized.redactedFields()).param("now", now)
                        .param("nextAutoReplayAt", nextAutoReplayAt).update();
            } catch (DuplicateKeyException conflict) {
                updateExisting(rejectionKey, sanitized, failure, payloadJson, now, nextAutoReplayAt);
            }
        }
        return idByKey(rejectionKey);
    }

    private int updateExisting(String rejectionKey, SanitizedPayload sanitized, ApiException failure,
                               String payloadJson, LocalDateTime now, LocalDateTime nextAutoReplayAt) {
        return jdbcClient.sql("""
                        UPDATE alert_ingest_rejection
                        SET delivery_count = delivery_count + 1,
                            error_code = :errorCode, error_message = :errorMessage,
                            payload_json = CASE WHEN payload_status = 'PURGED' THEN :payload ELSE payload_json END,
                            external_event_id = CASE WHEN payload_status = 'PURGED' THEN :externalEventId ELSE external_event_id END,
                            receiver = CASE WHEN payload_status = 'PURGED' THEN :receiver ELSE receiver END,
                            delivery_group_key = CASE WHEN payload_status = 'PURGED' THEN :groupKey ELSE delivery_group_key END,
                            alert_name = CASE WHEN payload_status = 'PURGED' THEN :alertName ELSE alert_name END,
                            resource_code = CASE WHEN payload_status = 'PURGED' THEN :resourceCode ELSE resource_code END,
                            severity = CASE WHEN payload_status = 'PURGED' THEN :severity ELSE severity END,
                            alert_status = CASE WHEN payload_status = 'PURGED' THEN :alertStatus ELSE alert_status END,
                            redacted_fields = CASE WHEN payload_status = 'PURGED' THEN :redactedFields ELSE redacted_fields END,
                            auto_replay_count = CASE WHEN payload_status = 'PURGED' THEN 0 ELSE auto_replay_count END,
                            next_auto_replay_at = CASE
                                WHEN payload_status = 'PURGED' THEN :nextAutoReplayAt
                                WHEN next_auto_replay_at IS NULL AND auto_replay_exhausted_at IS NULL
                                  THEN :nextAutoReplayAt ELSE next_auto_replay_at END,
                            auto_replay_exhausted_at = CASE WHEN payload_status = 'PURGED' THEN NULL ELSE auto_replay_exhausted_at END,
                            payload_status = 'ACTIVE', purged_at = NULL,
                            resolved_alert_id = NULL, resolved_incident_id = NULL,
                            resolved_by = NULL, resolved_at = NULL,
                            status = 'OPEN', last_received_at = :now,
                            version = version + 1, updated_at = :now
                        WHERE source = :source AND rejection_key = :rejectionKey
                        """)
                .param("errorCode", failure.code()).param("errorMessage", failureMessage(failure))
                .param("payload", payloadJson).param("externalEventId", sanitized.externalEventId())
                .param("receiver", sanitized.payload().receiver()).param("groupKey", sanitized.payload().groupKey())
                .param("alertName", sanitized.alertName()).param("resourceCode", sanitized.resourceCode())
                .param("severity", sanitized.severity()).param("alertStatus", sanitized.alertStatus())
                .param("redactedFields", sanitized.redactedFields()).param("nextAutoReplayAt", nextAutoReplayAt)
                .param("now", now).param("source", SOURCE).param("rejectionKey", rejectionKey).update();
    }

    @Transactional
    public void resolveMatching(AlertmanagerWebhookService.Alert alert, AlertService.IntakeResult result) {
        if (alert == null) return;
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        UPDATE alert_ingest_rejection
                        SET status = 'SUCCEEDED', resolved_alert_id = :alertId,
                            resolved_incident_id = :incidentId, resolved_at = :now,
                            replay_token = NULL, replay_lease_until = NULL,
                            next_auto_replay_at = NULL, auto_replay_exhausted_at = NULL,
                            last_replay_error_code = NULL, last_replay_error_message = NULL,
                            version = version + 1, updated_at = :now
                        WHERE source = :source AND rejection_key = :rejectionKey AND status = 'OPEN'
                        """)
                .param("alertId", result.alertId()).param("incidentId", result.incidentId())
                .param("now", now).param("source", SOURCE).param("rejectionKey", rejectionKey(alert)).update();
    }

    public PageResponse<RejectionView> list(String status, int page, int size) {
        String normalizedStatus = normalizeStatus(status);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String where = normalizedStatus == null ? "" : " WHERE status = :status";
        JdbcClient.StatementSpec count = jdbcClient.sql("SELECT COUNT(*) FROM alert_ingest_rejection" + where);
        JdbcClient.StatementSpec query = jdbcClient.sql("""
                        SELECT id, status, error_code, error_message, alert_name, resource_code,
                               severity, alert_status, receiver, delivery_count, replay_count,
                               redacted_fields, replay_lease_until, last_replay_error_code,
                               last_replay_error_message, resolved_alert_id, resolved_incident_id,
                               first_received_at, last_received_at, last_replayed_at, resolved_at, version,
                               payload_status, auto_replay_count, next_auto_replay_at,
                               auto_replay_exhausted_at, purged_at
                        FROM alert_ingest_rejection
                        """ + where + " ORDER BY last_received_at DESC, id DESC LIMIT :limit OFFSET :offset");
        if (normalizedStatus != null) {
            count = count.param("status", normalizedStatus);
            query = query.param("status", normalizedStatus);
        }
        long total = count.query(Long.class).single();
        List<RejectionView> items = query.param("limit", safeSize).param("offset", (safePage - 1) * safeSize)
                .query((rs, rowNum) -> new RejectionView(
                        rs.getLong("id"), rs.getString("status"), rs.getString("error_code"),
                        rs.getString("error_message"), rs.getString("alert_name"), rs.getString("resource_code"),
                        rs.getString("severity"), rs.getString("alert_status"), rs.getString("receiver"),
                        rs.getInt("delivery_count"), rs.getInt("replay_count"), rs.getInt("redacted_fields"),
                        rs.getObject("replay_lease_until", LocalDateTime.class), rs.getString("last_replay_error_code"),
                        rs.getString("last_replay_error_message"), nullableLong(rs, "resolved_alert_id"),
                        nullableLong(rs, "resolved_incident_id"), rs.getObject("first_received_at", LocalDateTime.class),
                        rs.getObject("last_received_at", LocalDateTime.class),
                        rs.getObject("last_replayed_at", LocalDateTime.class),
                        rs.getObject("resolved_at", LocalDateTime.class), rs.getInt("version"),
                        rs.getString("payload_status"), rs.getInt("auto_replay_count"),
                        rs.getObject("next_auto_replay_at", LocalDateTime.class),
                        rs.getObject("auto_replay_exhausted_at", LocalDateTime.class),
                        rs.getObject("purged_at", LocalDateTime.class), properties.isAutoReplayEnabled()))
                .list();
        return new PageResponse<>(items, total, safePage, safeSize);
    }

    @Transactional
    public ReplayClaim claim(long id) {
        RejectionRecord record = recordById(id);
        if ("SUCCEEDED".equals(record.status())) {
            return new ReplayClaim(null, true, null, record.resolvedAlertId(), record.resolvedIncidentId());
        }
        if ("PURGED".equals(record.payloadStatus())) {
            throw new ApiException(HttpStatus.GONE, "ALERTMANAGER_REJECTION_PAYLOAD_PURGED",
                    "拒绝项载荷已按保留期清理，需重新投递原始告警");
        }
        LocalDateTime now = LocalDateTime.now();
        String token = UUID.randomUUID().toString();
        int claimed = jdbcClient.sql("""
                        UPDATE alert_ingest_rejection
                        SET replay_token = :token, replay_lease_until = :leaseUntil,
                            replay_count = replay_count + 1, last_replayed_at = :now,
                            last_replay_error_code = NULL, last_replay_error_message = NULL,
                            version = version + 1, updated_at = :now
                        WHERE id = :id AND status = 'OPEN' AND payload_status = 'ACTIVE'
                          AND (replay_lease_until IS NULL OR replay_lease_until < :now)
                        """)
                .param("token", token).param("leaseUntil", now.plus(properties.getReplayLease()))
                .param("now", now).param("id", id).update();
        if (claimed == 0) {
            RejectionRecord current = recordById(id);
            if ("SUCCEEDED".equals(current.status())) {
                return new ReplayClaim(null, true, null, current.resolvedAlertId(), current.resolvedIncidentId());
            }
            if ("PURGED".equals(current.payloadStatus())) {
                throw new ApiException(HttpStatus.GONE, "ALERTMANAGER_REJECTION_PAYLOAD_PURGED",
                        "拒绝项载荷已按保留期清理，需重新投递原始告警");
            }
            throw new ApiException(HttpStatus.CONFLICT, "ALERTMANAGER_REPLAY_IN_PROGRESS",
                    "该拒绝项正在重放，请稍后刷新");
        }
        StoredPayload payload = read(recordById(id).payloadJson(), StoredPayload.class);
        return new ReplayClaim(token, false, payload, null, null);
    }

    public List<Long> dueAutomaticReplayIds(LocalDateTime now) {
        return jdbcClient.sql("""
                        SELECT id FROM alert_ingest_rejection
                        WHERE status = 'OPEN' AND payload_status = 'ACTIVE'
                          AND error_code = 'RESOURCE_NOT_FOUND'
                          AND next_auto_replay_at <= :now
                          AND auto_replay_count < :maxAttempts
                          AND (replay_lease_until IS NULL OR replay_lease_until < :now)
                        ORDER BY next_auto_replay_at, id LIMIT :batchSize
                        """)
                .param("now", now).param("maxAttempts", properties.getMaxAutoReplayAttempts())
                .param("batchSize", properties.getAutoReplayBatchSize()).query(Long.class).list();
    }

    @Transactional
    public Optional<ReplayClaim> claimAutomatic(long id, LocalDateTime now) {
        String token = UUID.randomUUID().toString();
        int claimed = jdbcClient.sql("""
                        UPDATE alert_ingest_rejection
                        SET replay_token = :token, replay_lease_until = :leaseUntil,
                            replay_count = replay_count + 1, auto_replay_count = auto_replay_count + 1,
                            last_replayed_at = :now, last_replay_error_code = NULL,
                            last_replay_error_message = NULL, version = version + 1, updated_at = :now
                        WHERE id = :id AND status = 'OPEN' AND payload_status = 'ACTIVE'
                          AND error_code = 'RESOURCE_NOT_FOUND'
                          AND next_auto_replay_at <= :now AND auto_replay_count < :maxAttempts
                          AND (replay_lease_until IS NULL OR replay_lease_until < :now)
                        """)
                .param("token", token).param("leaseUntil", now.plus(properties.getReplayLease()))
                .param("now", now).param("id", id)
                .param("maxAttempts", properties.getMaxAutoReplayAttempts()).update();
        if (claimed == 0) return Optional.empty();
        return Optional.of(new ReplayClaim(token, false, read(recordById(id).payloadJson(), StoredPayload.class),
                null, null));
    }

    @Transactional
    public void releaseAutomatic(long id, String token, String errorCode, String errorMessage, LocalDateTime now) {
        Integer attempts = jdbcClient.sql("""
                        SELECT auto_replay_count FROM alert_ingest_rejection
                        WHERE id = :id AND replay_token = :token
                        """).param("id", id).param("token", token).query(Integer.class).optional().orElse(null);
        if (attempts == null) return;
        boolean retryable = "RESOURCE_NOT_FOUND".equals(errorCode) || "REPLAY_INTERNAL_ERROR".equals(errorCode);
        boolean exhausted = !retryable || attempts >= properties.getMaxAutoReplayAttempts();
        LocalDateTime next = exhausted ? null : now.plus(retryDelay(attempts));
        jdbcClient.sql("""
                        UPDATE alert_ingest_rejection
                        SET replay_token = NULL, replay_lease_until = NULL,
                            next_auto_replay_at = :next,
                            auto_replay_exhausted_at = :exhaustedAt,
                            last_replay_error_code = :errorCode,
                            last_replay_error_message = :errorMessage,
                            version = version + 1, updated_at = :now
                        WHERE id = :id AND replay_token = :token
                        """)
                .param("next", next).param("exhaustedAt", exhausted ? now : null)
                .param("errorCode", errorCode).param("errorMessage", safeMessage(errorMessage))
                .param("now", now).param("id", id).param("token", token).update();
    }

    Duration retryDelay(int attempts) {
        long baseMillis = properties.getRetryBaseDelay().toMillis();
        long maxMillis = Math.max(baseMillis, properties.getRetryMaxDelay().toMillis());
        long cap = Math.min(maxMillis, baseMillis * (1L << Math.min(19, Math.max(0, attempts - 1))));
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(Math.max(1, cap / 2), cap + 1));
    }

    @Transactional
    public int purgeExpired(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(properties.getPayloadRetention());
        List<Long> ids = jdbcClient.sql("""
                        SELECT id FROM alert_ingest_rejection
                        WHERE payload_status = 'ACTIVE' AND last_received_at < :cutoff
                          AND (replay_lease_until IS NULL OR replay_lease_until < :now)
                        ORDER BY last_received_at, id LIMIT :batchSize
                        """)
                .param("cutoff", cutoff).param("now", now)
                .param("batchSize", properties.getCleanupBatchSize()).query(Long.class).list();
        int purged = 0;
        for (long id : ids) {
            purged += jdbcClient.sql("""
                            UPDATE alert_ingest_rejection
                            SET payload_json = '[PURGED]', payload_status = 'PURGED', purged_at = :now,
                                external_event_id = NULL, receiver = NULL, delivery_group_key = NULL,
                                alert_name = NULL, resource_code = NULL, severity = NULL,
                                alert_status = NULL, error_message = '载荷已按保留期清理',
                                last_replay_error_message = NULL, replay_token = NULL,
                                replay_lease_until = NULL, next_auto_replay_at = NULL,
                                version = version + 1, updated_at = :now
                            WHERE id = :id AND payload_status = 'ACTIVE' AND last_received_at < :cutoff
                              AND (replay_lease_until IS NULL OR replay_lease_until < :now)
                            """)
                    .param("now", now).param("id", id).param("cutoff", cutoff).update();
        }
        if (purged > 0) {
            auditService.recordAs(null, "scheduler", "ALERTMANAGER_REJECTION_PAYLOAD_PURGED",
                    "ALERT_INGEST_REJECTION", null, "count=" + purged);
        }
        return purged;
    }

    @Transactional
    public void complete(long id, String token, AlertService.IntakeResult result, Long actorId) {
        LocalDateTime now = LocalDateTime.now();
        int completed = jdbcClient.sql("""
                        UPDATE alert_ingest_rejection
                        SET status = 'SUCCEEDED', resolved_alert_id = :alertId,
                            resolved_incident_id = :incidentId, resolved_by = :actorId,
                            resolved_at = :now, replay_token = NULL, replay_lease_until = NULL,
                            last_replay_error_code = NULL, last_replay_error_message = NULL,
                            next_auto_replay_at = NULL, auto_replay_exhausted_at = NULL,
                            version = version + 1, updated_at = :now
                        WHERE id = :id AND replay_token = :token
                        """)
                .param("alertId", result.alertId()).param("incidentId", result.incidentId())
                .param("actorId", actorId).param("now", now).param("id", id).param("token", token).update();
        if (completed == 0 && !"SUCCEEDED".equals(recordById(id).status())) {
            throw new ApiException(HttpStatus.CONFLICT, "ALERTMANAGER_REPLAY_LEASE_LOST",
                    "重放租约已失效，请刷新后重试");
        }
    }

    @Transactional
    public void release(long id, String token, String errorCode, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        jdbcClient.sql("""
                        UPDATE alert_ingest_rejection
                        SET replay_token = NULL, replay_lease_until = NULL,
                            last_replay_error_code = :errorCode,
                            last_replay_error_message = :errorMessage,
                            version = version + 1, updated_at = :now
                        WHERE id = :id AND replay_token = :token
                        """)
                .param("errorCode", errorCode).param("errorMessage", safeMessage(errorMessage))
                .param("now", now).param("id", id).param("token", token).update();
    }

    private SanitizedPayload sanitize(AlertmanagerWebhookService.Webhook webhook,
                                      AlertmanagerWebhookService.Alert alert) {
        int redactedFields = 0;
        String receiver = redact(webhook == null ? null : webhook.receiver());
        String groupKey = redact(webhook == null ? null : webhook.groupKey());
        if (!Objects.equals(receiver, webhook == null ? null : webhook.receiver())) redactedFields++;
        if (!Objects.equals(groupKey, webhook == null ? null : webhook.groupKey())) redactedFields++;
        Map<String, String> commonAnnotations = webhook == null ? Map.of() : safeMap(webhook.commonAnnotations());
        redactedFields += changedFields(webhook == null ? null : webhook.commonAnnotations(), commonAnnotations);

        AlertmanagerWebhookService.Alert safeAlert = null;
        Map<String, String> labels = Map.of();
        if (alert != null) {
            labels = safeMap(alert.labels());
            Map<String, String> annotations = safeMap(alert.annotations());
            redactedFields += changedFields(alert.labels(), labels) + changedFields(alert.annotations(), annotations);
            String generatorUrl = redact(alert.generatorURL());
            String fingerprint = redact(alert.fingerprint());
            if (!Objects.equals(generatorUrl, alert.generatorURL())) redactedFields++;
            if (!Objects.equals(fingerprint, alert.fingerprint())) redactedFields++;
            safeAlert = new AlertmanagerWebhookService.Alert(alert.status(), labels, annotations,
                    alert.startsAt(), alert.endsAt(), generatorUrl, fingerprint);
        }
        StoredPayload payload = new StoredPayload(receiver, groupKey, commonAnnotations, safeAlert);
        return new SanitizedPayload(payload, redactedFields, safeExternalId(safeAlert), labels.get("alertname"),
                firstNonBlank(labels.get("resource_code"), labels.get("service_code")), labels.get("severity"),
                safeAlert == null ? null : safeAlert.status());
    }

    private String rejectionKey(AlertmanagerWebhookService.Alert alert) {
        if (alert != null && alert.fingerprint() != null && !alert.fingerprint().isBlank()
                && alert.startsAt() != null && alert.status() != null && !alert.status().isBlank()) {
            return sha256(SOURCE + ':' + alert.fingerprint().trim() + ':'
                    + alert.startsAt().toInstant().toEpochMilli() + ':' + alert.status().trim().toLowerCase());
        }
        Map<String, Object> canonical = new LinkedHashMap<>();
        if (alert == null) {
            canonical.put("alert", null);
        } else {
            canonical.put("status", alert.status());
            canonical.put("labels", new TreeMap<>(alert.labels() == null ? Map.of() : alert.labels()));
            canonical.put("annotations", new TreeMap<>(alert.annotations() == null ? Map.of() : alert.annotations()));
            canonical.put("startsAt", alert.startsAt());
            canonical.put("endsAt", alert.endsAt());
            canonical.put("generatorURL", alert.generatorURL());
            canonical.put("fingerprint", alert.fingerprint());
        }
        return sha256(json(canonical));
    }

    private RejectionRecord recordById(long id) {
        return jdbcClient.sql("""
                        SELECT status, payload_status, payload_json, resolved_alert_id, resolved_incident_id
                        FROM alert_ingest_rejection WHERE id = :id
                        """).param("id", id)
                .query((rs, rowNum) -> new RejectionRecord(rs.getString("status"), rs.getString("payload_status"),
                        rs.getString("payload_json"),
                        nullableLong(rs, "resolved_alert_id"), nullableLong(rs, "resolved_incident_id")))
                .optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "ALERTMANAGER_REJECTION_NOT_FOUND", "拒绝项不存在"));
    }

    private long idByKey(String rejectionKey) {
        return jdbcClient.sql("""
                        SELECT id FROM alert_ingest_rejection
                        WHERE source = :source AND rejection_key = :rejectionKey
                        """).param("source", SOURCE).param("rejectionKey", rejectionKey).query(Long.class).single();
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String normalized = status.trim().toUpperCase();
        if (!List.of("OPEN", "SUCCEEDED").contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ALERTMANAGER_REJECTION_STATUS_INVALID",
                    "status 必须是 OPEN 或 SUCCEEDED");
        }
        return normalized;
    }

    private Map<String, String> safeMap(Map<String, String> source) {
        return source == null ? Map.of() : redactor.redact(source);
    }

    private static int changedFields(Map<String, String> source, Map<String, String> safe) {
        if (source == null) return 0;
        int changed = 0;
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (!Objects.equals(entry.getValue(), safe.get(entry.getKey()))) changed++;
        }
        return changed;
    }

    private String redact(String value) {
        return value == null ? null : redactor.redact(value);
    }

    private static String safeExternalId(AlertmanagerWebhookService.Alert alert) {
        if (alert == null || alert.fingerprint() == null || alert.fingerprint().isBlank() || alert.startsAt() == null) {
            return null;
        }
        return bounded(alert.fingerprint().trim() + ':' + alert.startsAt().toInstant().toEpochMilli(), 128);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second == null || second.isBlank() ? null : second;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize Alertmanager rejection", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to read Alertmanager rejection", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private String failureMessage(ApiException failure) {
        return safeMessage(failure.getMessage());
    }

    private String safeMessage(String message) {
        return bounded(redactor.redact(message), 500);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public record StoredPayload(String receiver, String groupKey, Map<String, String> commonAnnotations,
                                AlertmanagerWebhookService.Alert alert) {
        AlertmanagerWebhookService.Webhook webhook() {
            return new AlertmanagerWebhookService.Webhook("4", groupKey, null, receiver,
                    Map.of(), Map.of(), commonAnnotations, null,
                    java.util.Collections.singletonList(alert));
        }
    }

    public record ReplayClaim(String token, boolean alreadySucceeded, StoredPayload payload,
                              Long resolvedAlertId, Long resolvedIncidentId) {
    }

    public record RejectionView(long id, String status, String errorCode, String errorMessage,
                                String alertName, String resourceCode, String severity, String alertStatus,
                                String receiver, int deliveryCount, int replayCount, int redactedFields,
                                LocalDateTime replayLeaseUntil, String lastReplayErrorCode,
                                String lastReplayErrorMessage, Long resolvedAlertId, Long resolvedIncidentId,
                                LocalDateTime firstReceivedAt, LocalDateTime lastReceivedAt,
                                LocalDateTime lastReplayedAt, LocalDateTime resolvedAt, int version,
                                String payloadStatus, int autoReplayCount, LocalDateTime nextAutoReplayAt,
                                LocalDateTime autoReplayExhaustedAt, LocalDateTime purgedAt,
                                boolean autoReplayEnabled) {
    }

    private record SanitizedPayload(StoredPayload payload, int redactedFields, String externalEventId,
                                    String alertName, String resourceCode, String severity, String alertStatus) {
    }

    private record RejectionRecord(String status, String payloadStatus, String payloadJson, Long resolvedAlertId,
                                   Long resolvedIncidentId) {
    }
}
