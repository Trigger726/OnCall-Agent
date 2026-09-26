package org.trigger.opspilot.oncall;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.security.UserPrincipal;

import java.util.List;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/on-call")
public class OnCallController {
    private final OnCallService service;
    private final IncidentEscalationService escalationService;
    private final OnCallRosterService rosterService;

    public OnCallController(OnCallService service, IncidentEscalationService escalationService,
                            OnCallRosterService rosterService) {
        this.service = service;
        this.escalationService = escalationService;
        this.rosterService = rosterService;
    }

    @GetMapping("/current")
    public ApiResponse<List<OnCallService.OnCallView>> current() {
        return ApiResponse.ok(service.current());
    }

    @GetMapping("/policies")
    public ApiResponse<List<OnCallService.PolicyView>> policies() {
        return ApiResponse.ok(service.policies());
    }

    @GetMapping("/roster")
    public ApiResponse<OnCallRosterService.RosterView> roster(
            @RequestParam(required = false) Long scheduleId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiResponse.ok(rosterService.roster(scheduleId, from, to));
    }

    @PostMapping("/shifts")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<OnCallRosterService.ShiftView> createShift(
            @Valid @RequestBody CreateShiftRequest body,
            @AuthenticationPrincipal UserPrincipal user, HttpServletRequest request) {
        return ApiResponse.ok(rosterService.create(new OnCallRosterService.ShiftCommand(body.scheduleId(),
                body.userId(), body.startsAt(), body.endsAt(), body.override(), body.note()),
                user.id(), request.getRemoteAddr()));
    }

    @PostMapping("/shifts/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<OnCallRosterService.ShiftView> cancelShift(
            @PathVariable long id, @Valid @RequestBody CancelShiftRequest body,
            @AuthenticationPrincipal UserPrincipal user, HttpServletRequest request) {
        return ApiResponse.ok(rosterService.cancel(id, body.version(), body.reason(), user.id(), request.getRemoteAddr()));
    }

    public record CreateShiftRequest(@Min(1) long scheduleId, @Min(1) long userId,
                                      @NotNull LocalDateTime startsAt, @NotNull LocalDateTime endsAt,
                                      boolean override, @NotBlank @Size(max = 500) String note) {}
    public record CancelShiftRequest(@Min(0) int version, @NotBlank @Size(max = 500) String reason) {}

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
