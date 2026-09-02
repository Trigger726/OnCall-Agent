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
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.common.PageResponse;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {
    private final AlertService service;

    public AlertController(AlertService service) {
        this.service = service;
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
    public ApiResponse<AlertService.IntakeResult> intake(@Valid @RequestBody IntakeRequest request) {
        return ApiResponse.ok(service.intake(new AlertService.IntakeRequest(
                request.source(), request.externalEventId(), request.resourceCode(), request.severity(),
                request.status(), request.title(), request.description(), request.labels(), request.occurredAt())));
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
