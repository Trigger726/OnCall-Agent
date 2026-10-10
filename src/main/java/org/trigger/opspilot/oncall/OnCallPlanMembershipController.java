package org.trigger.opspilot.oncall;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.security.UserPrincipal;

import java.util.List;

@RestController
@RequestMapping("/api/v1/on-call/schedules/{scheduleId}/members")
public class OnCallPlanMembershipController {
    private final OnCallPlanMembershipService service;
    public OnCallPlanMembershipController(OnCallPlanMembershipService service) { this.service = service; }
    @GetMapping
    public ApiResponse<List<OnCallPlanMembershipService.MemberView>> list(@PathVariable long scheduleId) { return ApiResponse.ok(service.list(scheduleId)); }
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<OnCallPlanMembershipService.ChangeResult> change(@PathVariable long scheduleId, @Valid @RequestBody Change body,
            @AuthenticationPrincipal UserPrincipal user, HttpServletRequest request) {
        return ApiResponse.ok(service.change(scheduleId, new OnCallPlanMembershipService.Command(body.userId(),body.expectedVersion(),body.active(),body.canRespond(),body.canManage(),body.operationKey(),body.reason()), user.id(),request.getRemoteAddr()));
    }
    public record Change(@NotNull @Min(1) Long userId, @JsonProperty(value="expectedVersion",required=true) @Min(0) Integer expectedVersion,
                         @NotNull Boolean active,@NotNull Boolean canRespond,@NotNull Boolean canManage,
                         @NotBlank @Size(max=36) String operationKey,@NotBlank @Size(max=500) String reason) {}
}
