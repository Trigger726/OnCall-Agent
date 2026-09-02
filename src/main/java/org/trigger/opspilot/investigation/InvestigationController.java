package org.trigger.opspilot.investigation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.security.UserPrincipal;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/v1/incidents")
public class InvestigationController {
    private final InvestigationService service;
    private final AgentRunEventService eventService;
    private final AgentExecutionManager executionManager;

    public InvestigationController(InvestigationService service, AgentRunEventService eventService,
                                   AgentExecutionManager executionManager) {
        this.service = service;
        this.eventService = eventService;
        this.executionManager = executionManager;
    }

    @PostMapping("/{id}/investigations")
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public ApiResponse<InvestigationService.InvestigationResult> investigate(
            @PathVariable long id,
            @RequestParam(defaultValue = "INCIDENT_WORKSPACE") String source) {
        return ApiResponse.ok(service.investigate(id, source));
    }

    @PostMapping(value = "/{id}/investigations/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','OPS_MANAGER','ON_CALL')")
    public SseEmitter investigateStream(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable long id,
            @RequestParam(defaultValue = "INCIDENT_WORKSPACE") String source,
            @RequestParam(required = false) Long timeoutMs,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request,
            HttpServletResponse response) {
        InvestigationService.RunActor actor = new InvestigationService.RunActor(
                user.id(), request.getRemoteAddr());
        InvestigationService.PreparedRun prepared = service.prepare(id, source, actor, idempotencyKey,
                timeoutMs == null ? null : Duration.ofMillis(timeoutMs));
        response.setHeader("X-OpsPilot-Run-Id", String.valueOf(prepared.runId()));
        response.setHeader("X-OpsPilot-Idempotent-Replay", String.valueOf(prepared.reused()));
        SseEmitter emitter = new SseEmitter(300_000L);
        AtomicBoolean connected = new AtomicBoolean(true);
        emitter.onCompletion(() -> connected.set(false));
        emitter.onTimeout(() -> connected.set(false));
        emitter.onError(error -> connected.set(false));
        AgentRunEventService.EventSink sink = emitterSink(emitter, connected);
        eventService.list(prepared.runId(), 0).forEach(sink::publish);
        if (prepared.reused()) {
            emitter.complete();
            return emitter;
        }
        try {
            executionManager.submit(prepared.runId(), prepared.deadlineAt(), () -> {
            try {
                service.execute(prepared, actor, sink);
                if (connected.get()) emitter.complete();
            } catch (RuntimeException exception) {
                if (!connected.get()) return;
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(new StreamError("RUN_FAILED", safeMessage(exception))));
                    emitter.complete();
                } catch (IOException | IllegalStateException sendException) {
                    connected.set(false);
                }
            }
            }, () -> service.requestTimeout(prepared.runId()));
        } catch (TaskRejectedException exception) {
            service.rejectQueue(prepared.runId(), exception, sink);
            if (connected.get()) emitter.complete();
        }
        return emitter;
    }

    static AgentRunEventService.EventSink emitterSink(SseEmitter emitter, AtomicBoolean connected) {
        return event -> {
            if (!connected.get()) return;
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(event.id()))
                        .name(event.eventType().toLowerCase(Locale.ROOT))
                        .reconnectTime(2_000L)
                        .data(event));
            } catch (IOException | IllegalStateException exception) {
                connected.set(false);
            }
        };
    }

    @GetMapping("/{id}/agent-runs")
    public ApiResponse<List<AgentRunQueryService.AgentRunView>> runs(@PathVariable long id) {
        return ApiResponse.ok(service.listRuns(id));
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Agent 调查失败" : message;
    }

    public record StreamError(String type, String message) {
    }
}
