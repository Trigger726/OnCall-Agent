package org.trigger.opspilot.runbook;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.security.UserPrincipal;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/runbooks")
public class RunbookController {
    private final RunbookService service;
    private final RunbookSemanticIndexService semanticIndexService;
    private final RunbookRetrievalFeedbackService feedbackService;

    public RunbookController(RunbookService service, RunbookSemanticIndexService semanticIndexService,
                             RunbookRetrievalFeedbackService feedbackService) {
        this.service = service;
        this.semanticIndexService = semanticIndexService;
        this.feedbackService = feedbackService;
    }

    @GetMapping
    public ApiResponse<List<RunbookService.DocumentView>> list(
            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok(service.listPublished(user.roleCode()));
    }

    @GetMapping("/search")
    public ApiResponse<RunbookService.SearchResponse> search(
            @AuthenticationPrincipal UserPrincipal user,
            @RequestParam("q") @NotBlank @Size(max = 500) String query,
            @RequestParam(defaultValue = "5") @Min(1) @Max(10) int topK,
            @RequestParam(defaultValue = "AUTO") String mode) {
        return ApiResponse.ok(service.searchTracked(query, user.roleCode(), user.id(), topK, mode, "CONSOLE"));
    }

    @PostMapping("/searches/{searchId}/judgments")
    public ApiResponse<RunbookRetrievalFeedbackService.JudgmentView> submitJudgment(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable long searchId,
            @Valid @RequestBody JudgmentRequest request) {
        return ApiResponse.ok(feedbackService.submit(searchId, request.documentStableKey(),
                request.relevanceGrade(), request.comment(), user.id(), user.roleCode()));
    }

    @GetMapping("/judgments/pending")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<List<RunbookRetrievalFeedbackService.PendingJudgmentView>> pendingJudgments(
            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok(feedbackService.pending(user.id()));
    }

    @GetMapping("/judgments/agreement")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<RunbookRetrievalFeedbackService.AgreementView> judgmentAgreement() {
        return ApiResponse.ok(feedbackService.agreement());
    }

    @PostMapping("/judgments/{judgmentId}/reviews")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<RunbookRetrievalFeedbackService.JudgmentView> reviewJudgment(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable long judgmentId,
            @Valid @RequestBody JudgmentReviewRequest request) {
        return ApiResponse.ok(feedbackService.review(judgmentId, request.expectedVersion(),
                request.decision(), request.reviewerGrade(), request.note(), user.id()));
    }

    @GetMapping("/{stableKey}/versions")
    public ApiResponse<List<RunbookService.DocumentView>> versions(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable String stableKey) {
        return ApiResponse.ok(service.versions(stableKey, user.roleCode()));
    }

    @PostMapping("/imports/markdown")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<RunbookService.ImportResult> importMarkdown(
            @AuthenticationPrincipal UserPrincipal user,
            @Valid @RequestBody MarkdownImportRequest request) {
        return ApiResponse.ok(service.importMarkdown(new RunbookService.ImportCommand(
                request.stableKey(), request.resourceType(), request.serviceCode(), request.title(),
                request.summary(), request.sourceName(), request.markdown(), request.allowedRoles()), user.id()));
    }

    @PostMapping(value = "/imports/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<RunbookService.ImportResult> importFile(
            @AuthenticationPrincipal UserPrincipal user,
            @RequestParam @NotBlank String stableKey,
            @RequestParam @NotBlank String resourceType,
            @RequestParam(required = false) String serviceCode,
            @RequestParam @NotBlank String title,
            @RequestParam(required = false) String summary,
            @RequestParam(required = false) List<String> allowedRoles,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(service.importFile(new RunbookService.FileImportCommand(
                stableKey, resourceType, serviceCode, title, summary, allowedRoles), file, user.id()));
    }

    @PostMapping("/evaluations")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<RunbookService.EvaluationView> evaluate(
            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok(service.evaluate(user.id()));
    }

    @GetMapping("/evaluations/latest")
    public ApiResponse<RunbookService.EvaluationView> latestEvaluation() {
        return ApiResponse.ok(service.latestEvaluation());
    }

    @GetMapping("/semantic-index")
    public ApiResponse<RunbookSemanticIndexService.IndexStatus> semanticIndex() {
        return ApiResponse.ok(semanticIndexService.status());
    }

    @PostMapping("/semantic-index/rebuild")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<RunbookSemanticIndexService.IndexBuildResult> rebuildSemanticIndex(
            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok(semanticIndexService.rebuild(user.id()));
    }

    public record MarkdownImportRequest(
            @NotBlank @Size(max = 80) String stableKey,
            @NotBlank @Size(max = 32) String resourceType,
            @Size(max = 80) String serviceCode,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 1_000) String summary,
            @NotBlank @Size(max = 255) String sourceName,
            @NotBlank @Size(max = 5_242_880) String markdown,
            List<String> allowedRoles) {
    }

    public record JudgmentRequest(
            @NotBlank @Size(max = 80) String documentStableKey,
            @Min(0) @Max(3) int relevanceGrade,
            @Size(max = 500) String comment) {
    }

    public record JudgmentReviewRequest(
            @Min(0) int expectedVersion,
            @NotBlank String decision,
            @Min(0) @Max(3) Integer reviewerGrade,
            @Size(max = 500) String note) {
    }
}
