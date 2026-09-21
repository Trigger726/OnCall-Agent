package org.trigger.opspilot.slo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.security.UserPrincipal;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/slo/objectives")
public class ServiceSloController {
    private final ServiceSloService service;

    public ServiceSloController(ServiceSloService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<ServiceSloService.SloOverview> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
        return ApiResponse.ok(service.overview(at));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER')")
    public ApiResponse<ServiceSloService.ObjectiveView> update(
            @PathVariable long id, @AuthenticationPrincipal UserPrincipal user,
            @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.ok(service.update(id, new ServiceSloService.UpdateCommand(
                request.expectedVersion(), request.name(), request.targetPercent(), request.windowDays(),
                request.goodEventsQueryTemplate(), request.totalEventsQueryTemplate()), user.id()));
    }

    public record UpdateRequest(@NotNull @Min(0) Integer expectedVersion,
                                @NotBlank @Size(max = 120) String name,
                                @DecimalMin(value = "0.001") @DecimalMax(value = "99.999") double targetPercent,
                                @Min(1) @Max(90) int windowDays,
                                @NotBlank @Size(max = 1000) String goodEventsQueryTemplate,
                                @NotBlank @Size(max = 1000) String totalEventsQueryTemplate) {
    }
}
