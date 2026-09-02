package org.trigger.opspilot.remediation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.security.UserPrincipal;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class RemediationProposalController {
    private final RemediationProposalService service;

    public RemediationProposalController(RemediationProposalService service) {
        this.service = service;
    }

    @GetMapping("/incidents/{incidentId}/remediation-proposals")
    public ApiResponse<List<RemediationProposalService.ProposalView>> list(@PathVariable long incidentId) {
        return ApiResponse.ok(service.listByIncident(incidentId));
    }

    @PostMapping("/remediation-proposals/{proposalId}/reviews")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<RemediationProposalService.ProposalView> review(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable long proposalId,
            @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.ok(service.review(proposalId, user.id(), request.decision(),
                request.version(), request.comment()));
    }

    public record ReviewRequest(@NotNull RemediationProposalService.Decision decision,
                                @Min(1) int version,
                                @NotBlank @Size(min = 3, max = 500) String comment) {
    }
}
