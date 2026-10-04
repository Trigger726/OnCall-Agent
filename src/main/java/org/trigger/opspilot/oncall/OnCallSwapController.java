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
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.security.UserPrincipal;

@RestController
@RequestMapping("/api/v1/on-call/swaps")
public class OnCallSwapController {
    private final OnCallSwapService service;
    private final OnCallSwapNotifications notifications;
    public OnCallSwapController(OnCallSwapService service, OnCallSwapNotifications notifications) { this.service = service; this.notifications = notifications; }

    @GetMapping
    public ApiResponse<OnCallSwapService.ListView> list(@RequestParam(required = false) Long scheduleId,
            @RequestParam(defaultValue = "ALL") String scope, @RequestParam(required = false) String status,
            @AuthenticationPrincipal UserPrincipal user) {
        if (!scope.equals("ALL") && !scope.equals("MINE")) throw new ApiException(HttpStatus.BAD_REQUEST, "ONCALL_SWAP_INVALID", "范围须为ALL或MINE");
        return ApiResponse.ok(service.list(scheduleId, scope.equals("MINE") ? user.id() : null, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<OnCallSwapService.View> get(@PathVariable long id) { return ApiResponse.ok(service.get(id)); }

    @GetMapping("/{id}/notifications")
    public ApiResponse<OnCallSwapNotifications.ListView> notifications(@PathVariable long id) { return ApiResponse.ok(notifications.list(id)); }

    @PostMapping("/{id}/notifications/{notificationId}/retry")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<OnCallSwapNotifications.View> retryNotification(@PathVariable long id, @PathVariable long notificationId,
            @Valid @RequestBody RetryNotification body, @AuthenticationPrincipal UserPrincipal user, HttpServletRequest request) {
        return ApiResponse.ok(notifications.retry(id,notificationId,body.version(),body.reason(),user.id(),request.getRemoteAddr()));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<OnCallSwapService.View> request(@Valid @RequestBody Request body,
            @AuthenticationPrincipal UserPrincipal user, HttpServletRequest request) {
        return ApiResponse.ok(service.request(new OnCallSwapService.Command(body.firstShiftId(), body.firstVersion(),
                body.secondShiftId(), body.secondVersion(), body.requestKey(), body.reason()), user.id(), request.getRemoteAddr()));
    }

    @PostMapping("/{id}/decisions")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<OnCallSwapService.View> decide(@PathVariable long id, @Valid @RequestBody Decision body,
            @AuthenticationPrincipal UserPrincipal user, HttpServletRequest request) {
        return ApiResponse.ok(service.decide(id, new OnCallSwapService.Decision(body.version(), body.status(), body.reason()), user.id(), request.getRemoteAddr()));
    }

    public record Request(@NotNull @Min(1) Long firstShiftId, @NotNull @Min(0) Integer firstVersion,
                          @NotNull @Min(1) Long secondShiftId, @NotNull @Min(0) Integer secondVersion,
                          @NotBlank @Size(max = 36) String requestKey, @NotBlank @Size(max = 500) String reason) {}
    public record Decision(@NotNull @Min(0) Integer version, @NotBlank String status, @NotBlank @Size(max = 500) String reason) {}
    public record RetryNotification(@NotNull @Min(0) Integer version, @NotBlank @Size(max = 500) String reason) {}
}
