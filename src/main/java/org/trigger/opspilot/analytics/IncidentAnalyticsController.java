package org.trigger.opspilot.analytics;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/analytics/incidents")
public class IncidentAnalyticsController {
    private final IncidentAnalyticsService service;

    public IncidentAnalyticsController(IncidentAnalyticsService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<IncidentAnalyticsService.IncidentAnalyticsView> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String severity) {
        return ApiResponse.ok(service.overview(from, to, severity));
    }
}
