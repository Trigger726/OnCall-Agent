package org.trigger.opspilot.postmortem;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.PastOrPresent;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.common.PageResponse;
import org.trigger.opspilot.security.UserPrincipal;

import java.time.LocalDate;

@Validated
@RestController
@RequestMapping("/api/v1/postmortem-follow-ups")
public class FollowUpOperationsController {
    private final FollowUpOperationsService operationsService;
    private final FollowUpEscalationService escalationService;

    public FollowUpOperationsController(FollowUpOperationsService operationsService,
                                        FollowUpEscalationService escalationService) {
        this.operationsService = operationsService;
        this.escalationService = escalationService;
    }

    @GetMapping
    public ApiResponse<PageResponse<FollowUpOperationsService.FollowUpOperationsView>> list(
            @AuthenticationPrincipal UserPrincipal user,
            @RequestParam(defaultValue = "ALL") String scope,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(operationsService.list(
                user.id(), scope, status, overdue, asOf, page, size));
    }

    @PostMapping("/escalations/run")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<FollowUpEscalationService.EscalationScanResult> runEscalations(
            @AuthenticationPrincipal UserPrincipal user,
            HttpServletRequest request,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @PastOrPresent LocalDate asOf) {
        return ApiResponse.ok(escalationService.scan(asOf, user.id(), request.getRemoteAddr()));
    }
}
