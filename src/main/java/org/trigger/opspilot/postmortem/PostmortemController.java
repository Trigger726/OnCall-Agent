package org.trigger.opspilot.postmortem;

import jakarta.validation.Valid;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.security.UserPrincipal;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1")
public class PostmortemController {
    private final PostmortemService service;

    public PostmortemController(PostmortemService service) {
        this.service = service;
    }

    @GetMapping("/incidents/{incidentId}/postmortem")
    public ApiResponse<PostmortemService.PostmortemView> byIncident(@PathVariable long incidentId) {
        return ApiResponse.ok(service.findByIncident(incidentId));
    }

    @PostMapping("/incidents/{incidentId}/postmortem")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<PostmortemService.PostmortemView> create(
            @PathVariable long incidentId, @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok(service.createDraft(incidentId, user.id()));
    }

    @PatchMapping("/postmortems/{postmortemId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<PostmortemService.PostmortemView> update(
            @PathVariable long postmortemId, @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.ok(service.update(postmortemId, request.expectedVersion(),
                new PostmortemService.DraftContent(request.summary(), request.customerImpact(),
                        request.rootCause(), request.contributingFactors(), request.lessonsLearned())));
    }

    @PostMapping("/postmortems/{postmortemId}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<PostmortemService.PostmortemView> submit(
            @PathVariable long postmortemId, @AuthenticationPrincipal UserPrincipal user,
            @Valid @RequestBody VersionRequest request) {
        return ApiResponse.ok(service.submit(postmortemId, request.expectedVersion(), user.id()));
    }

    @PostMapping("/postmortems/{postmortemId}/reviews")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<PostmortemService.PostmortemView> review(
            @PathVariable long postmortemId, @AuthenticationPrincipal UserPrincipal user,
            @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.ok(service.review(postmortemId, request.expectedVersion(), user.id(),
                request.decision(), request.comment()));
    }

    @PostMapping("/postmortems/{postmortemId}/follow-ups")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<PostmortemService.PostmortemView> addFollowUp(
            @PathVariable long postmortemId, @AuthenticationPrincipal UserPrincipal user,
            @Valid @RequestBody FollowUpRequest request) {
        return ApiResponse.ok(service.addFollowUp(postmortemId, request.expectedPostmortemVersion(),
                user.id(), request.toContent()));
    }

    @PatchMapping("/postmortem-follow-ups/{followUpId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<PostmortemService.PostmortemView> updateFollowUp(
            @PathVariable long followUpId, @Valid @RequestBody FollowUpRequest request) {
        return ApiResponse.ok(service.updateFollowUp(followUpId, request.expectedPostmortemVersion(),
                request.expectedVersion(), request.toContent()));
    }

    @PostMapping("/postmortem-follow-ups/{followUpId}/complete")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<PostmortemService.PostmortemView> completeFollowUp(
            @PathVariable long followUpId, @AuthenticationPrincipal UserPrincipal user,
            @Valid @RequestBody VersionRequest request) {
        return ApiResponse.ok(service.completeFollowUp(followUpId, request.expectedVersion(),
                user.id(), user.roleCode()));
    }

    public record UpdateRequest(@Min(0) int expectedVersion,
                                @NotBlank @Size(max = 4000) String summary,
                                @NotBlank @Size(max = 4000) String customerImpact,
                                @NotBlank @Size(max = 4000) String rootCause,
                                @NotBlank @Size(max = 4000) String contributingFactors,
                                @NotBlank @Size(max = 4000) String lessonsLearned) {
    }

    public record VersionRequest(@Min(0) int expectedVersion) {
    }

    public record ReviewRequest(@Min(0) int expectedVersion,
                                @NotNull PostmortemService.ReviewDecision decision,
                                @NotBlank @Size(min = 3, max = 500) String comment) {
    }

    public record FollowUpRequest(@Min(0) int expectedPostmortemVersion,
                                  @Min(0) int expectedVersion,
                                  @NotBlank @Size(max = 240) String title,
                                  @NotBlank @Size(max = 1000) String description,
                                  @NotNull PostmortemService.Priority priority,
                                  @NotNull Long ownerId,
                                  @NotNull @FutureOrPresent LocalDate dueDate) {
        PostmortemService.FollowUpContent toContent() {
            return new PostmortemService.FollowUpContent(
                    title, description, priority, ownerId, dueDate);
        }
    }
}
