package org.trigger.opspilot.oncall;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/v1/on-call")
public class OnCallController {
    private final OnCallService service;

    public OnCallController(OnCallService service) {
        this.service = service;
    }

    @GetMapping("/current")
    public ApiResponse<List<OnCallService.OnCallView>> current() {
        return ApiResponse.ok(service.current());
    }

    @GetMapping("/policies")
    public ApiResponse<List<OnCallService.PolicyView>> policies() {
        return ApiResponse.ok(service.policies());
    }
}
