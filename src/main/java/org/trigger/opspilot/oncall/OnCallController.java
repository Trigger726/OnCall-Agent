package org.trigger.opspilot.oncall;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.security.UserPrincipal;

import java.util.List;

@RestController
@RequestMapping("/api/v1/on-call")
public class OnCallController {
    private final OnCallService service;
    private final IncidentEscalationService escalationService;

    public OnCallController(OnCallService service, IncidentEscalationService escalationService) {
        this.service = service;
        this.escalationService = escalationService;
    }

    @GetMapping("/current")
    public ApiResponse<List<OnCallService.OnCallView>> current() {
        return ApiResponse.ok(service.current());
    }

    @GetMapping("/policies")
    public ApiResponse<List<OnCallService.PolicyView>> policies() {
        return ApiResponse.ok(service.policies());
    }

    @GetMapping("/escalations")
    public ApiResponse<List<IncidentEscalationService.EventView>> escalations() {
        return ApiResponse.ok(escalationService.recent());
    }

    @PostMapping("/escalations/scan")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<IncidentEscalationService.ScanResult> scan(
            @AuthenticationPrincipal UserPrincipal user, HttpServletRequest request) {
        return ApiResponse.ok(escalationService.scan(null, user.id(), request.getRemoteAddr()));
    }
}
