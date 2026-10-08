package org.trigger.opspilot.common;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerStreamResponseTest {
    record Rejection(RuntimeException exception, int status, String code) {}

    static Stream<Object[]> rejections() {
        return Stream.of(
                new Rejection(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_STREAM_SATURATED", "输出容量已满"), 503, "ASSISTANT_STREAM_SATURATED"),
                new Rejection(new ApiException(HttpStatus.NOT_FOUND, "ASSISTANT_SESSION_NOT_FOUND", "会话不存在"), 404, "ASSISTANT_SESSION_NOT_FOUND"),
                new Rejection(new ApiException(HttpStatus.CONFLICT, "ASSISTANT_REQUEST_RUNNING", "原请求正在执行"), 409, "ASSISTANT_REQUEST_RUNNING"),
                new Rejection(new CredentialsExpiredException("expired"), 401, "AUTHENTICATION_REQUIRED"),
                new Rejection(new AccessDeniedException("denied"), 403, "ACCESS_DENIED"),
                new Rejection(new ConstraintViolationException("invalid", Set.of()), 400, "VALIDATION_ERROR"),
                new Rejection(new HttpMessageNotReadableException("invalid", new MockHttpInputMessage(new byte[0])), 400, "INVALID_JSON")
        ).flatMap(rejection -> Stream.of("text/event-stream", "text/event-stream, application/json")
                .map(accept -> new Object[] {rejection, accept}));
    }

    @ParameterizedTest(name = "{0} accept={1}")
    @MethodSource("rejections")
    void shouldPreserveTypedJsonRejectionBeforeSseAdmission(Rejection rejection, String accept) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new RejectedStream(rejection.exception()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/rejected-stream").accept(accept))
                .andExpect(status().is(rejection.status()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(rejection.code()));
    }

    @RestController
    static class RejectedStream {
        private final RuntimeException rejection;
        RejectedStream(RuntimeException rejection) { this.rejection = rejection; }
        @PostMapping(value = "/rejected-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        String stream() { throw rejection; }
    }
}
