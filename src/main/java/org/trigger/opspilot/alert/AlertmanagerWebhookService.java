package org.trigger.opspilot.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.common.PageResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AlertmanagerWebhookService {
    private static final Logger log = LoggerFactory.getLogger(AlertmanagerWebhookService.class);
    private static final String AUTHORIZATION_PREFIX = "OpsPilot ";
    private final AlertmanagerWebhookProperties properties;
    private final AlertService alertService;
    private final AlertmanagerRejectionService rejectionService;
    private final AuditService auditService;

    public AlertmanagerWebhookService(AlertmanagerWebhookProperties properties, AlertService alertService,
                                      AlertmanagerRejectionService rejectionService, AuditService auditService) {
        this.properties = properties;
        this.alertService = alertService;
        this.rejectionService = rejectionService;
        this.auditService = auditService;
    }

    public DeliveryResult receive(String authorization, Webhook webhook) {
        authenticate(authorization);
        List<Alert> alerts = webhook == null || webhook.alerts() == null ? List.of() : webhook.alerts();
        if (alerts.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ALERTMANAGER_EMPTY_BATCH",
                    "Alertmanager webhook 必须至少包含一条 alert");
        }
        if (alerts.size() > properties.getMaxAlerts()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ALERTMANAGER_BATCH_TOO_LARGE",
                    "Alertmanager webhook 单批最多接收 " + properties.getMaxAlerts() + " 条 alert");
        }

        List<ItemResult> items = new ArrayList<>();
        int accepted = 0;
        int replayed = 0;
        int updated = 0;
        int rejected = 0;
        for (int index = 0; index < alerts.size(); index++) {
            Alert alert = alerts.get(index);
            try {
                AlertService.IntakeResult result = intake(toCommand(alert, webhook));
                rejectionService.resolveMatching(alert, result);
                items.add(new ItemResult(index, result.action(), result.alertId(), result.incidentId(),
                        externalId(alert), null, null, result.message()));
                accepted++;
                if ("REPLAYED".equals(result.action())) replayed++;
                if ("UPDATED".equals(result.action())) updated++;
            } catch (ApiException exception) {
                rejected++;
                long rejectionId = rejectionService.record(webhook, alert, exception);
                log.warn("Alertmanager webhook item rejected: index={}, code={}", index, exception.code());
                items.add(new ItemResult(index, "REJECTED", null, null, safeExternalId(alert),
                        rejectionId, exception.code(), exception.getMessage()));
            }
        }
        return new DeliveryResult(webhook.receiver(), webhook.groupKey(), alerts.size(), accepted,
                replayed, updated, rejected, items);
    }

    public PageResponse<AlertmanagerRejectionService.RejectionView> rejections(String status, int page, int size) {
        return rejectionService.list(status, page, size);
    }

    public ReplayResult replay(long rejectionId, Long actorId) {
        AlertmanagerRejectionService.ReplayClaim claim = rejectionService.claim(rejectionId);
        if (claim.alreadySucceeded()) {
            return new ReplayResult("ALREADY_SUCCEEDED", rejectionId, claim.resolvedAlertId(),
                    claim.resolvedIncidentId(), "该拒绝项此前已成功重放，本次未重复执行");
        }
        try {
            AlertmanagerRejectionService.StoredPayload stored = claim.payload();
            AlertService.IntakeResult result = intake(toCommand(stored.alert(), stored.webhook()));
            rejectionService.complete(rejectionId, claim.token(), result, actorId);
            auditService.record("ALERTMANAGER_REJECTION_REPLAYED", "ALERT_INGEST_REJECTION", rejectionId,
                    "action=" + result.action() + ", alertId=" + result.alertId()
                            + ", incidentId=" + result.incidentId());
            return new ReplayResult(result.action(), rejectionId, result.alertId(), result.incidentId(),
                    "拒绝项已重放：" + result.message());
        } catch (ApiException exception) {
            rejectionService.release(rejectionId, claim.token(), exception.code(), exception.getMessage());
            auditService.record("ALERTMANAGER_REJECTION_REPLAY_FAILED", "ALERT_INGEST_REJECTION", rejectionId,
                    "errorCode=" + exception.code());
            throw exception;
        } catch (RuntimeException exception) {
            rejectionService.release(rejectionId, claim.token(), "REPLAY_INTERNAL_ERROR", "重放暂时失败，请稍后重试");
            auditService.record("ALERTMANAGER_REJECTION_REPLAY_FAILED", "ALERT_INGEST_REJECTION", rejectionId,
                    "errorCode=REPLAY_INTERNAL_ERROR");
            throw exception;
        }
    }

    private AlertService.IntakeResult intake(AlertService.IntakeRequest command) {
        try {
            return alertService.intake(command);
        } catch (DataIntegrityViolationException conflict) {
            return alertService.intake(command);
        }
    }

    private AlertService.IntakeRequest toCommand(Alert alert, Webhook webhook) {
        if (alert == null) {
            throw invalid("ALERTMANAGER_ALERT_REQUIRED", "alert 不能为空");
        }
        Map<String, String> labels = alert.labels() == null ? Map.of() : alert.labels();
        Map<String, String> annotations = alert.annotations() == null ? Map.of() : alert.annotations();
        String resourceCode = firstPresent(labels, "resource_code", "service_code");
        if (resourceCode == null) {
            throw invalid("ALERTMANAGER_RESOURCE_REQUIRED",
                    "labels.resource_code 或 labels.service_code 必填");
        }
        String severity = mapSeverity(labels.get("severity"));
        String status = mapStatus(alert.status());
        String alertName = required(labels.get("alertname"), "ALERTMANAGER_ALERTNAME_REQUIRED",
                "labels.alertname 必填");
        if (alertName.length() > 240) {
            throw invalid("ALERTMANAGER_ALERTNAME_TOO_LONG", "labels.alertname 不能超过 240 个字符");
        }
        OffsetDateTime startsAt = alert.startsAt();
        if (startsAt == null) {
            throw invalid("ALERTMANAGER_START_REQUIRED", "startsAt 必填");
        }
        LocalDateTime occurredAt = "RESOLVED".equals(status)
                ? utc(alert.endsAt(), "ALERTMANAGER_END_REQUIRED", "resolved alert 的 endsAt 必填")
                : utc(startsAt, "ALERTMANAGER_START_REQUIRED", "startsAt 必填");

        Map<String, String> storedLabels = new LinkedHashMap<>(labels);
        if (webhook.receiver() != null && !webhook.receiver().isBlank()) {
            storedLabels.put("opspilot_receiver", webhook.receiver());
        }
        if (alert.generatorURL() != null && !alert.generatorURL().isBlank()) {
            storedLabels.put("opspilot_generator_url", alert.generatorURL());
        }
        String summary = firstNonBlank(annotations.get("summary"),
                webhook.commonAnnotations() == null ? null : webhook.commonAnnotations().get("summary"));
        String description = firstNonBlank(annotations.get("description"),
                webhook.commonAnnotations() == null ? null : webhook.commonAnnotations().get("description"));
        String detail = summary == null ? description
                : description == null || summary.equals(description) ? summary : summary + "\n\n" + description;
        return new AlertService.IntakeRequest("alertmanager", externalId(alert), resourceCode, severity,
                status, alertName, detail, storedLabels, occurredAt);
    }

    private void authenticate(String authorization) {
        if (!properties.enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ALERTMANAGER_WEBHOOK_DISABLED",
                    "Alertmanager webhook 尚未配置密钥");
        }
        String supplied = authorization != null && authorization.startsWith(AUTHORIZATION_PREFIX)
                ? authorization.substring(AUTHORIZATION_PREFIX.length()) : "";
        if (!MessageDigest.isEqual(properties.getSecret().getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ALERTMANAGER_WEBHOOK_UNAUTHORIZED",
                    "Alertmanager webhook 凭证无效");
        }
    }

    private static String externalId(Alert alert) {
        String fingerprint = required(alert.fingerprint(), "ALERTMANAGER_FINGERPRINT_REQUIRED",
                "fingerprint 必填");
        if (fingerprint.length() > 100) {
            throw invalid("ALERTMANAGER_FINGERPRINT_TOO_LONG", "fingerprint 不能超过 100 个字符");
        }
        if (alert.startsAt() == null) {
            throw invalid("ALERTMANAGER_START_REQUIRED", "startsAt 必填");
        }
        return fingerprint + ':' + alert.startsAt().toInstant().toEpochMilli();
    }

    private static String safeExternalId(Alert alert) {
        try {
            return alert == null ? null : externalId(alert);
        } catch (ApiException ignored) {
            return null;
        }
    }

    private static String mapSeverity(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "P1", "P2", "P3", "P4" -> normalized;
            case "CRITICAL" -> "P1";
            case "WARNING" -> "P2";
            case "INFO" -> "P3";
            default -> throw invalid("ALERTMANAGER_SEVERITY_UNMAPPED",
                    "labels.severity 必须是 P1-P4、critical、warning 或 info");
        };
    }

    private static String mapStatus(String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
            case "firing" -> "FIRING";
            case "resolved" -> "RESOLVED";
            default -> throw invalid("ALERTMANAGER_STATUS_UNMAPPED", "alert.status 必须是 firing 或 resolved");
        };
    }

    private static LocalDateTime utc(OffsetDateTime value, String code, String message) {
        if (value == null) throw invalid(code, message);
        return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static String required(String value, String code, String message) {
        if (value == null || value.isBlank()) throw invalid(code, message);
        return value.trim();
    }

    private static String firstPresent(Map<String, String> values, String first, String second) {
        return firstNonBlank(values.get(first), values.get(second));
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first.trim();
        return second == null || second.isBlank() ? null : second.trim();
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public record Webhook(String version, String groupKey, String status, String receiver,
                          Map<String, String> groupLabels, Map<String, String> commonLabels,
                          Map<String, String> commonAnnotations, String externalURL,
                          List<Alert> alerts) {
    }

    public record Alert(String status, Map<String, String> labels, Map<String, String> annotations,
                        OffsetDateTime startsAt, OffsetDateTime endsAt, String generatorURL,
                        String fingerprint) {
    }

    public record ItemResult(int index, String action, Long alertId, Long incidentId,
                             String externalEventId, Long rejectionId, String errorCode, String message) {
    }

    public record DeliveryResult(String receiver, String groupKey, int received, int accepted,
                                 int replayed, int updated, int rejected, List<ItemResult> items) {
    }

    public record ReplayResult(String action, long rejectionId, Long alertId, Long incidentId, String message) {
    }
}
