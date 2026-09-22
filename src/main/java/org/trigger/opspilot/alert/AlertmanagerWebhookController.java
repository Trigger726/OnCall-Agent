package org.trigger.opspilot.alert;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;

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
}
