package org.trigger.opspilot.alert;

import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.common.PageResponse;
import org.trigger.opspilot.security.UserPrincipal;

@RestController
@RequestMapping("/api/v1/integrations/alertmanager")
public class AlertmanagerWebhookController {
    private final AlertmanagerWebhookService service;

    public AlertmanagerWebhookController(AlertmanagerWebhookService service) {
        this.service = service;
    }

    @PostMapping("/webhook")
    public ApiResponse<AlertmanagerWebhookService.DeliveryResult> webhook(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) AlertmanagerWebhookService.Webhook webhook) {
        return ApiResponse.ok(service.receive(authorization, webhook));
    }

    @GetMapping("/rejections")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL','AUDITOR')")
    public ApiResponse<PageResponse<AlertmanagerRejectionService.RejectionView>> rejections(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.rejections(status, page, size));
    }

    @PostMapping("/rejections/{id}/replay")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<AlertmanagerWebhookService.ReplayResult> replay(
            @PathVariable long id, @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(service.replay(id, principal.id()));
    }
}
