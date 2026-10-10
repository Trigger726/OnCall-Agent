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
@RequestMapping("/api/v1/on-call/open-handoffs")
public class OnCallOpenHandoffController {
    private final OnCallOpenHandoffService service;
    private final OnCallOpenHandoffRecipients recipients;
    private final OnCallOpenNotifications notifications;
    public OnCallOpenHandoffController(OnCallOpenHandoffService service, OnCallOpenHandoffRecipients recipients,OnCallOpenNotifications notifications) { this.service = service; this.recipients = recipients; this.notifications=notifications; }

    @GetMapping
    public ApiResponse<OnCallOpenHandoffService.ListView> list(@RequestParam(required=false) Long scheduleId,
            @RequestParam(defaultValue="ALL") String scope, @RequestParam(required=false) String status,
            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok(service.list(scheduleId, user.id(), scope, status));
    }
    @GetMapping("/{id}")
    public ApiResponse<OnCallOpenHandoffService.View> get(@PathVariable long id) { return ApiResponse.ok(service.get(id)); }
    @GetMapping("/{id}/coverage")
    public ApiResponse<OnCallOpenHandoffService.CoverageView> coverage(@PathVariable long id) { return ApiResponse.ok(service.coverage(id)); }
    @GetMapping("/{id}/publication")
    public ApiResponse<OnCallOpenHandoffRecipients.View> publication(@PathVariable long id) { return ApiResponse.ok(recipients.get(id)); }
    @GetMapping("/{id}/notifications")
    public ApiResponse<OnCallOpenNotifications.ListView> notifications(@PathVariable long id) { return ApiResponse.ok(notifications.list(id)); }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<OnCallOpenHandoffService.View> request(@Valid @RequestBody Request body,
            @AuthenticationPrincipal UserPrincipal user, HttpServletRequest request) {
        return ApiResponse.ok(service.request(new OnCallOpenHandoffService.Command(body.sourceShiftId(), body.sourceVersion(),
                body.requestKey(), body.startsAt(), body.endsAt(), body.reason()), user.id(), request.getRemoteAddr()));
    }
    @PostMapping("/{id}/claims")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<OnCallOpenHandoffService.CoverageView> claim(@PathVariable long id, @Valid @RequestBody Operation body,
            @AuthenticationPrincipal UserPrincipal user, HttpServletRequest request) {
        return ApiResponse.ok(service.claim(id, new OnCallOpenHandoffService.OperationCommand(body.version(), body.operationKey(), body.reason()), user.id(), request.getRemoteAddr()));
    }
    @PostMapping("/{id}/withdrawals")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<OnCallOpenHandoffService.CoverageView> withdraw(@PathVariable long id, @Valid @RequestBody Operation body,
            @AuthenticationPrincipal UserPrincipal user, HttpServletRequest request) {
        return ApiResponse.ok(service.withdraw(id, new OnCallOpenHandoffService.OperationCommand(body.version(), body.operationKey(), body.reason()), user.id(), request.getRemoteAddr()));
    }
    public record Request(@NotNull @Min(1) Long sourceShiftId, @NotNull @Min(0) Integer sourceVersion,
                          @NotBlank @Size(max=36) String requestKey, @NotNull LocalDateTime startsAt,
                          @NotNull LocalDateTime endsAt, @NotBlank @Size(max=500) String reason) {}
    public record Operation(@NotNull @Min(0) Integer version, @NotBlank @Size(max=36) String operationKey,
                            @NotBlank @Size(max=500) String reason) {}
}
