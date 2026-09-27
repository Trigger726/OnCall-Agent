package org.trigger.opspilot.oncall;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.security.UserPrincipal;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/on-call/handoffs")
public class OnCallHandoffController {
    private final OnCallHandoffService service;
    public OnCallHandoffController(OnCallHandoffService service) { this.service = service; }

    @GetMapping
    public ApiResponse<OnCallHandoffService.ListView> list(@RequestParam(required=false) Long scheduleId,
            @RequestParam(defaultValue="ALL") String scope, @RequestParam(required=false) String status,
            @AuthenticationPrincipal UserPrincipal user) {
        if (!scope.equals("ALL") && !scope.equals("MINE")) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"ONCALL_HANDOFF_INVALID","范围须为 ALL 或 MINE");
        }
        return ApiResponse.ok(service.list(scheduleId, scope.equals("MINE") ? user.id() : null, status));
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
