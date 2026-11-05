package org.trigger.opspilot.cmdb;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/v1/cmdb")
public class CmdbController {
    private final CmdbService service;

    public CmdbController(CmdbService service) {
        this.service = service;
    }

    @GetMapping("/resources")
    public ApiResponse<PageResponse<CmdbService.ResourceSummary>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.list(q, type, status, page, size));
    }

    @GetMapping("/resources/{id}")
    public ApiResponse<CmdbService.ResourceDetail> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/topology")
    public ApiResponse<CmdbService.Topology> topology() {
        return ApiResponse.ok(service.topology());
    }

    @PostMapping("/resources")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<CmdbService.ResourceDetail> create(@Valid @RequestBody CmdbService.ResourceCommand command) {
        return ApiResponse.ok(service.save(null, command));
    }

    @PutMapping("/resources/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<CmdbService.ResourceDetail> update(@PathVariable long id, @Valid @RequestBody CmdbService.ResourceCommand command) {
        return ApiResponse.ok(service.save(id, command));
    }
}
