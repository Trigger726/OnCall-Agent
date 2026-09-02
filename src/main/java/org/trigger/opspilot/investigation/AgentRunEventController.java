package org.trigger.opspilot.investigation;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.security.UserPrincipal;

import java.util.List;

@RestController
@RequestMapping("/api/v1/agent-runs")
public class AgentRunEventController {
    private final AgentRunEventService eventService;
    private final InvestigationService investigationService;
    private final AgentExecutionManager executionManager;

    public AgentRunEventController(AgentRunEventService eventService,
                                   InvestigationService investigationService,
                                   AgentExecutionManager executionManager) {
        this.eventService = eventService;
        this.investigationService = investigationService;
        this.executionManager = executionManager;
    }

    @GetMapping("/{runId}/events")
    public ApiResponse<List<AgentRunEventService.EventView>> events(
            @PathVariable long runId,
            @RequestParam(defaultValue = "0") long after) {
        return ApiResponse.ok(eventService.list(runId, after));
    }

    @PostMapping("/{runId}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<AgentRunQueryService.AgentRunView> cancel(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable long runId,
            @RequestBody(required = false) CancelRequest body,
            HttpServletRequest request) {
        AgentRunQueryService.AgentRunView run = investigationService.requestCancellation(
                runId, new InvestigationService.RunActor(user.id(), request.getRemoteAddr()),
                body == null ? null : body.reason());
        executionManager.cancel(runId);
        return ApiResponse.ok(run);
    }

    public record CancelRequest(String reason) {
    }
}
