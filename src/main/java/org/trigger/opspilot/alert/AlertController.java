package org.trigger.opspilot.alert;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.trigger.opspilot.audit.AuditService;
import org.trigger.opspilot.security.SessionAuthorization;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.common.PageResponse;
import org.trigger.opspilot.observability.tracing.OpsPilotTracing;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {
    private final AlertService service;
    private final OpsPilotTracing tracing;
    private final SessionAuthorization authorization;
    private final AuditService audit;

    public AlertController(AlertService service, OpsPilotTracing tracing, SessionAuthorization authorization, AuditService audit) {
        this.service = service;
        this.tracing = tracing;
        this.authorization = authorization;
        this.audit = audit;
    }

    @GetMapping
    public ApiResponse<PageResponse<AlertService.AlertView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(status, severity, page, size));
    }

    @PostMapping("/intake")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApiResponse<AlertService.IntakeResult> intake(@Valid @RequestBody IntakeRequest request) {
        // Recheck current account/version/role after obtaining its transaction lock, not only at JWT parsing.
        if (!authorization.authorized(authorization.current(), true, true)) {
            throw new CredentialsExpiredException("Verified intake session required");
        }
        AlertService.IntakeRequest command = new AlertService.IntakeRequest(
                request.source(), request.externalEventId(), request.resourceCode(), request.severity(),
                request.status(), request.title(), request.description(), request.labels(), request.occurredAt());
        var result = tracing.traceAlertIntake(command, () -> service.intake(command));
        // Keep audit metadata bounded; never store the Authorization header, payload, or caller-provided labels here.
        audit.record("ALERT_INTAKE", "ALERT_EVENT", result.alertId(),
                "授权兼容接入；action=" + result.action() + ";incidentId=" + result.incidentId());
        return ApiResponse.ok(result);
    }

    public record IntakeRequest(
            @NotBlank String source,
            String externalEventId,
            @NotBlank String resourceCode,
            @NotBlank @Pattern(regexp = "P[1-4]", message = "必须是 P1-P4") String severity,
            @Pattern(regexp = "FIRING|ACKNOWLEDGED|RESOLVED", message = "状态不合法") String status,
            @NotBlank String title,
            String description,
            Map<String, String> labels,
            LocalDateTime occurredAt) {
    }
}
