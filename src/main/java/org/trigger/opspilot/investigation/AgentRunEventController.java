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
    private final AgentEventSubscriptions subscriptions;

    public AgentRunEventController(AgentRunEventService eventService,
                                   InvestigationService investigationService,
                                   AgentExecutionManager executionManager,
                                   AgentEventSubscriptions subscriptions) {
        this.eventService = eventService;
        this.investigationService = investigationService;
        this.executionManager = executionManager;
        this.subscriptions = subscriptions;
    }

    @GetMapping(value = "/{runId}/events/stream", produces = "text/event-stream")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(
            @PathVariable long runId,
            @RequestParam(required = false) String after,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        String cursor = after != null ? after : lastEventId;
        try {
            return subscriptions.open(runId, cursor == null ? 0 : Long.parseLong(cursor));
        } catch (NumberFormatException exception) {
            throw new org.trigger.opspilot.common.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "AGENT_CURSOR_INVALID", "事件游标必须为非负整数");
        }
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
