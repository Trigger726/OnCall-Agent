package org.trigger.opspilot.oncall;

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

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/on-call/handoffs")
public class OnCallHandoffController {
    private final OnCallHandoffService service;
    public OnCallHandoffController(OnCallHandoffService service) { this.service = service; }

    @GetMapping
    public ApiResponse<OnCallHandoffService.ListView> list(@RequestParam(required=false) Long scheduleId) {
        return ApiResponse.ok(service.list(scheduleId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<OnCallHandoffService.View> request(@Valid @RequestBody Request body,
            @AuthenticationPrincipal UserPrincipal user, HttpServletRequest request) {
        return ApiResponse.ok(service.request(new OnCallHandoffService.Command(body.sourceShiftId(), body.sourceVersion(),
                body.targetUserId(), body.requestKey(), body.startsAt(), body.endsAt(), body.reason()), user.id(), request.getRemoteAddr()));
    }

    @PostMapping("/{id}/decisions")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<OnCallHandoffService.View> decide(@PathVariable long id, @Valid @RequestBody Decision body,
            @AuthenticationPrincipal UserPrincipal user, HttpServletRequest request) {
        return ApiResponse.ok(service.decide(id, new OnCallHandoffService.Decision(body.version(), body.status(), body.reason()),
                user.id(), request.getRemoteAddr()));
    }

    public record Request(@Min(1) long sourceShiftId, @Min(0) int sourceVersion, @Min(1) long targetUserId,
                          @NotBlank @Size(max=36) String requestKey, @NotNull LocalDateTime startsAt,
                          @NotNull LocalDateTime endsAt, @NotBlank @Size(max=500) String reason) {}
    public record Decision(@Min(0) int version, @NotBlank String status, @NotBlank @Size(max=500) String reason) {}
}
