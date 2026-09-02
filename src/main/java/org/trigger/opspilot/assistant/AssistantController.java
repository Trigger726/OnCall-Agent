package org.trigger.opspilot.assistant;

import jakarta.annotation.PreDestroy;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {
    private final AssistantService service;
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public AssistantController(AssistantService service) {
        this.service = service;
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
        SseEmitter emitter = new SseEmitter(300_000L);
        long ownerId = user.id();
        streamExecutor.execute(() -> {
            try {
                AssistantService.MessageView message = service.sendMessage(id, ownerId, request.content());
                emitter.send(SseEmitter.event().name("meta")
                        .data(new StreamEvent("meta", "", message.id(), message.evidenceJson())));
                for (String chunk : chunks(message.content(), 28)) {
                    emitter.send(SseEmitter.event().name("message")
                            .data(new StreamEvent("delta", chunk, message.id(), null)));
                }
                emitter.send(SseEmitter.event().name("done")
                        .data(new StreamEvent("done", "", message.id(), message.evidenceJson())));
                emitter.complete();
            } catch (Exception exception) {
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(new StreamEvent("error", exception.getMessage(), null, null)));
                    emitter.complete();
                } catch (IOException ioException) {
                    emitter.completeWithError(ioException);
                }
            }
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

    @PreDestroy
    void shutdownExecutor() {
        streamExecutor.shutdownNow();
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
