package org.trigger.opspilot.problem;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.common.PageResponse;
import org.trigger.opspilot.security.UserPrincipal;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/problems")
public class ProblemController {
    private final ProblemService service;

    public ProblemController(ProblemService service) {
        this.service = service;
    }

    @GetMapping("/recurrence-candidates")
    public ApiResponse<PageResponse<ProblemService.RecurrenceCandidate>> candidates(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long serviceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.recurrenceCandidates(from, to, serviceId, page, size));
    }

    @GetMapping
    public ApiResponse<PageResponse<ProblemService.ProblemView>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProblemService.ProblemView> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<ProblemService.ProblemCreateResult> create(
            @Valid @RequestBody CreateRequest body,
            @AuthenticationPrincipal UserPrincipal user,
            HttpServletRequest request) {
        return ApiResponse.ok(service.create(
                body.recurrenceKey(), body.from(), body.to(), user.id(), request.getRemoteAddr()));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<ProblemService.ProblemView> update(
            @PathVariable long id,
            @Valid @RequestBody UpdateRequest body,
            @AuthenticationPrincipal UserPrincipal user,
            HttpServletRequest request) {
        return ApiResponse.ok(service.update(id, body.expectedVersion(),
                new ProblemService.ProblemUpdate(body.title(), body.status(), body.ownerId(),
                        body.rootCause(), body.workaround(), body.resolutionSummary()),
                user.id(), request.getRemoteAddr()));
    }

    public record CreateRequest(
            @NotBlank
            @Pattern(regexp = "[1-9][0-9]*:[0-9a-f]{64}") String recurrenceKey,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    }

    public record UpdateRequest(
            @NotNull @Min(0) Integer expectedVersion,
            @Size(max = 240) String title,
            ProblemStatus status,
            @Positive Long ownerId,
            @Size(max = 4_000) String rootCause,
            @Size(max = 4_000) String workaround,
            @Size(max = 2_000) String resolutionSummary) {
    }
}
