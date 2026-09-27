package org.trigger.opspilot.common;

import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerDisconnectTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    @Test
    void shouldNotWriteAnotherBodyToADisconnectedSocketOrReportSuccess() {
        var response = new MockHttpServletResponse();
        handler.handleDisconnectedClient(new ClientAbortException(new IOException("Broken pipe")), response);
        assertThat(response.getStatus()).isEqualTo(499);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getContentType()).isNull();
    }
    @Test
    void shouldLeaveAnAlreadyCommittedResponseUnchanged() throws Exception {
        var response = new MockHttpServletResponse();
        response.setStatus(206);
        response.flushBuffer();
        handler.handleDisconnectedClient(new ClientAbortException(new IOException("Connection reset")), response);
        assertThat(response.getStatus()).isEqualTo(206);
        assertThat(response.getContentAsByteArray()).isEmpty();
    }
    @Test
    void shouldNotHideOutboundProviderIOFailuresBasedOnTheirMessage() {
        var response = handler.handleUnexpected(new ResourceAccessException("provider unavailable", new IOException("Broken pipe")));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
    }
    @Test
    void shouldResolveTheSpecificContainerExceptionBeforeTheGenericHandler() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new DisconnectController()).setControllerAdvice(handler).build();
        var response = mvc.perform(get("/disconnect-test")).andExpect(status().is(499)).andReturn().getResponse();
        assertThat(response.getContentAsByteArray()).isEmpty();
    }
    @RestController
    static class DisconnectController {
        @GetMapping("/disconnect-test")
        String disconnect() throws ClientAbortException { throw new ClientAbortException(new IOException("Broken pipe")); }
    }
}
