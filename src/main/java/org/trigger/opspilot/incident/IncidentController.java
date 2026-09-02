package org.trigger.opspilot.incident;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
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

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {
    private final IncidentService service;

    public IncidentController(IncidentService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<IncidentService.IncidentSummary>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(status, severity, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<IncidentService.IncidentDetail> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<IncidentService.IncidentSummary> transition(
            @PathVariable long id, @Valid @RequestBody TransitionRequest request) {
        return ApiResponse.ok(service.transition(id, request.targetStatus(), request.version(), request.note()));
    }

    @PatchMapping("/{id}/assignee")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<IncidentService.IncidentSummary> assign(
            @PathVariable long id, @Valid @RequestBody AssignRequest request) {
        return ApiResponse.ok(service.assign(id, request.assigneeId(), request.version()));
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<Void> addNote(@PathVariable long id, @Valid @RequestBody NoteRequest request) {
        service.addNote(id, request.content(), request.evidenceRef());
        return ApiResponse.ok(null);
    }

    public record TransitionRequest(@NotNull IncidentStatus targetStatus, @Min(0) int version, String note) {
    }

    public record AssignRequest(@NotNull Long assigneeId, @Min(0) int version) {
    }

    public record NoteRequest(@NotBlank String content, String evidenceRef) {
    }
}
