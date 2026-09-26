package org.trigger.opspilot.oncall;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.security.UserPrincipal;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/on-call/rotations")
public class OnCallRotationController {
    private final OnCallRotationService service;
    public OnCallRotationController(OnCallRotationService service) { this.service = service; }

    @GetMapping
    public ApiResponse<OnCallRotationService.RotationList> list(@RequestParam(required = false) Long scheduleId) {
        return ApiResponse.ok(service.list(scheduleId));
    }

    @GetMapping("/{id}")
    public ApiResponse<OnCallRotationService.RotationView> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<OnCallRotationService.RotationView> create(@RequestBody OnCallRotationService.Command body,
            @AuthenticationPrincipal UserPrincipal actor, HttpServletRequest request) {
        return ApiResponse.ok(service.create(body, actor.id(), request.getRemoteAddr()));
    }

    @GetMapping("/{id}/slots")
    public ApiResponse<OnCallRotationService.SlotWindow> slots(@PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiResponse.ok(service.slots(id, from, to));
    }

    @PostMapping("/{id}/state")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<OnCallRotationService.RotationView> state(@PathVariable long id, @Valid @RequestBody StateRequest body,
            @AuthenticationPrincipal UserPrincipal actor, HttpServletRequest request) {
        return ApiResponse.ok(service.state(id, body.version(), body.active(), body.reason(), actor.id(), request.getRemoteAddr()));
    }

    @PostMapping("/scan")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<OnCallRotationService.ScanResult> scan(@AuthenticationPrincipal UserPrincipal actor,
                                                            HttpServletRequest request) {
        return ApiResponse.ok(service.scan(null, actor.id(), request.getRemoteAddr()));
    }

    public record StateRequest(@Min(0) int version, boolean active, @NotBlank @Size(max = 500) String reason) {}
}
