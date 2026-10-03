package org.trigger.opspilot.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.security.UserPrincipal;
import org.trigger.opspilot.security.SessionAuthorization;
import org.springframework.security.authentication.CredentialsExpiredException;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {
    private final AssistantService service;
    private final SessionAuthorization authorization;
    private final AssistantExecutionManager execution;

    public AssistantController(AssistantService service, SessionAuthorization authorization, AssistantExecutionManager execution) {
        this.service = service;
        this.authorization = authorization;
        this.execution = execution;
    }

    @GetMapping("/sessions")
    public ApiResponse<List<AssistantService.SessionSummary>> list(@AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok(service.listSessions(user.id()));
    }

    @PostMapping("/sessions")
    public ApiResponse<AssistantService.SessionDetail> create(
            @AuthenticationPrincipal UserPrincipal user, @RequestBody CreateSessionRequest request) {
        return ApiResponse.ok(service.createSession(user.id(), request.incidentId(), request.title()));
    }

    @GetMapping("/sessions/{id}")
    public ApiResponse<AssistantService.SessionDetail> get(
            @AuthenticationPrincipal UserPrincipal user, @PathVariable long id) {
        return ApiResponse.ok(service.getSession(id, user.id()));
    }

    @PostMapping("/sessions/{id}/messages")
    public ApiResponse<AssistantService.MessageView> message(
            @AuthenticationPrincipal UserPrincipal user, @PathVariable long id,
            @Valid @RequestBody SendMessageRequest request) {
        return ApiResponse.ok(service.sendMessage(id, user.id(), request.content()));
    }

    @PostMapping(value = "/sessions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @AuthenticationPrincipal UserPrincipal user, @PathVariable long id,
            @Valid @RequestBody SendMessageRequest request) {
        var lease = authorization.current();
        service.getSession(id, user.id()); // Ownership is checked before admitting any queued work.
        SseEmitter emitter = new SseEmitter(300_000L);
        long ownerId = user.id();
        execution.submit(lease, work -> {
            try {
                AssistantService.MessageView message = service.sendMessage(id, ownerId, request.content(), lease, work::check);
                work.check();
                emitter.send(SseEmitter.event().name("meta")
                        .data(new StreamEvent("meta", "", message.id(), message.evidenceJson())));
                for (String chunk : chunks(message.content(), 28)) {
                    work.check();
                    emitter.send(SseEmitter.event().name("message")
                            .data(new StreamEvent("delta", chunk, message.id(), null)));
                }
                work.check();
                emitter.send(SseEmitter.event().name("done")
                        .data(new StreamEvent("done", "", message.id(), message.evidenceJson())));
                emitter.complete();
            } catch (CredentialsExpiredException exception) {
                emitter.complete(); // Never send an answer/error payload under revoked credentials.
            } catch (Exception exception) {
                try {
                    work.check();
                    emitter.send(SseEmitter.event().name("error")
                            .data(new StreamEvent("error", "回答未完成，请稍后重试", null, null)));
                    emitter.complete();
                } catch (Exception stopped) {
                    emitter.complete();
                }
            }
        }, reason -> {
            try {
                if (reason == AssistantExecutionManager.Reason.TIMED_OUT && authorization.authorized(lease, false)) {
                    emitter.send(SseEmitter.event().name("error")
                            .data(new StreamEvent("error", "回答超时，请稍后重新发送问题", null, null)));
                }
            } catch (Exception ignored) {
                // Fail closed if authorization cannot be checked or the transport is already closed.
            } finally { emitter.complete(); }
        });
        return emitter;
    }

    @DeleteMapping("/sessions/{id}/messages")
    public ApiResponse<Void> clear(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id) {
        service.clearMessages(id, user.id());
        return ApiResponse.ok(null);
    }

    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id) {
        service.deleteSession(id, user.id());
        return ApiResponse.ok(null);
    }

    @GetMapping(value = "/sessions/{id}/export", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal UserPrincipal user, @PathVariable long id) {
        String markdown = service.exportMarkdown(id, user.id());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=opspilot-conversation-" + id + ".md")
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(markdown.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> chunks(String content, int chunkSize) {
        if (content == null || content.isEmpty()) return List.of();
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        for (int start = 0; start < content.length(); start += chunkSize) {
            result.add(content.substring(start, Math.min(content.length(), start + chunkSize)));
        }
        return result;
    }

    public record CreateSessionRequest(Long incidentId, @Size(max = 160) String title) {
    }

    public record SendMessageRequest(@NotBlank @Size(max = 10000) String content) {
    }

    public record StreamEvent(String type, String content, Long messageId, String evidenceJson) {
    }
}
