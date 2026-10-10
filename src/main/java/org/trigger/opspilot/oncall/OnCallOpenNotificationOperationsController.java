package org.trigger.opspilot.oncall;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.security.UserPrincipal;

@RestController
public class OnCallOpenNotificationOperationsController {
    private final OnCallOpenNotificationOperations operations;
    public OnCallOpenNotificationOperationsController(OnCallOpenNotificationOperations operations){this.operations=operations;}
    @GetMapping("/api/v1/on-call/open-notification-operations")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<OnCallOpenNotificationOperations.View> get(@AuthenticationPrincipal UserPrincipal actor){return ApiResponse.ok(operations.get(actor.id()));}
}
