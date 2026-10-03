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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.http.HttpStatus;
import org.trigger.opspilot.common.ApiException;
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
    public DeferredResult<ApiResponse<AssistantService.MessageView>> message(
            @AuthenticationPrincipal UserPrincipal user, @PathVariable long id,
            @Valid @RequestBody SendMessageRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey,
            jakarta.servlet.http.HttpServletResponse response) {
        var lease = authorization.current();
        long ownerId = user.id();
        service.getSession(id, ownerId);
        String keyHash = AssistantRequestStore.keyHash(requestKey);
        String attemptId = java.util.UUID.randomUUID().toString();
        var result = new DeferredResult<ApiResponse<AssistantService.MessageView>>(0L);
        if (keyHash != null) {
            var replay = service.replay(id, ownerId, request.content(), keyHash, lease);
            if (replay != null) {
                requireLease(lease);
                response.setHeader("X-OpsPilot-Idempotent-Replay", "true");
                result.setResult(ApiResponse.ok(replay));
                return result;
            }
        }
        execution.submit(lease, work -> {
            try {
                var identity = keyHash == null ? null : new AssistantRequestStore.Identity(keyHash, attemptId, work.deadlineEpochMillis());
                var message = service.sendMessage(id, ownerId, request.content(), lease, work::check, identity);
                work.check(); // Never replace the original HTTP lease with a newly captured account version.
                if (service.replayedByDifferentAttempt(id, keyHash, attemptId)) response.setHeader("X-OpsPilot-Idempotent-Replay", "true");
                work.check();
                result.setResult(ApiResponse.ok(message));
            } catch (Exception exception) {
                result.setErrorResult(exception);
            }
        }, reason -> {
            try { service.stopRequest(id, keyHash, attemptId, reason); }
            catch (RuntimeException ignored) { /* Safe response still stops; persisted deadline permits later reconciliation. */ }
            boolean authorized;
            try { authorized = authorization.authorized(lease, false); }
            catch (RuntimeException exception) { authorized = false; }
            if (!authorized || reason == AssistantExecutionManager.Reason.REVOKED) {
                result.setErrorResult(new CredentialsExpiredException("Assistant session expired"));
            } else if (reason == AssistantExecutionManager.Reason.TIMED_OUT) {
                result.setErrorResult(new ApiException(HttpStatus.GATEWAY_TIMEOUT,
                        "ASSISTANT_EXECUTION_TIMEOUT", "回答超时，请稍后重新发送问题"));
            } else {
                result.setErrorResult(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "ASSISTANT_EXECUTION_STOPPED", "回答已停止，请稍后重试"));
            }
        });
        return result;
    }

    @PostMapping(value = "/sessions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @AuthenticationPrincipal UserPrincipal user, @PathVariable long id,
            @Valid @RequestBody SendMessageRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestKey,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        var lease = authorization.current();
        service.getSession(id, user.id()); // Ownership is checked before admitting any queued work.
        SseEmitter emitter = new SseEmitter(300_000L);
        long ownerId = user.id();
        String keyHash = AssistantRequestStore.keyHash(requestKey);
        String attemptId = java.util.UUID.randomUUID().toString();
        if (keyHash != null) {
            var replay = service.replay(id, ownerId, request.content(), keyHash, lease);
            if (replay != null) {
                response.setHeader("X-OpsPilot-Idempotent-Replay", "true");
                sendEvents(emitter, replay, () -> requireLease(lease));
                return emitter;
            }
        }
        execution.submit(lease, work -> {
            try {
                var identity = keyHash == null ? null : new AssistantRequestStore.Identity(keyHash, attemptId, work.deadlineEpochMillis());
                var message = service.sendMessage(id, ownerId, request.content(), lease, work::check, identity);
                if (service.replayedByDifferentAttempt(id, keyHash, attemptId)) response.setHeader("X-OpsPilot-Idempotent-Replay", "true");
                sendEvents(emitter, message, work::check);
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
                try { service.stopRequest(id, keyHash, attemptId, reason); }
                catch (RuntimeException ignored) { /* Stop transport even if the state store is unavailable. */ }
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

    @GetMapping("/sessions/{id}/request")
    public ApiResponse<AssistantRequestStore.View> requestStatus(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable long id, @RequestHeader(value = "Idempotency-Key", required = false) String requestKey) {
        String keyHash = AssistantRequestStore.keyHash(requestKey);
        if (keyHash == null) throw new ApiException(HttpStatus.BAD_REQUEST, "ASSISTANT_IDEMPOTENCY_KEY_REQUIRED", "需要Idempotency-Key");
        return ApiResponse.ok(service.requestStatus(id, user.id(), keyHash));
    }

    private void requireLease(SessionAuthorization.Lease lease) {
        if (!authorization.authorized(lease, false)) throw new CredentialsExpiredException("Assistant session expired");
    }

    private static void sendEvents(SseEmitter emitter, AssistantService.MessageView message, Runnable checkpoint) throws java.io.IOException {
        checkpoint.run();
        emitter.send(SseEmitter.event().name("meta").data(new StreamEvent("meta", "", message.id(), message.evidenceJson())));
        for (String chunk : chunks(message.content(), 28)) {
            checkpoint.run();
            emitter.send(SseEmitter.event().name("message").data(new StreamEvent("delta", chunk, message.id(), null)));
        }
        checkpoint.run();
        emitter.send(SseEmitter.event().name("done").data(new StreamEvent("done", "", message.id(), message.evidenceJson())));
        emitter.complete();
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
