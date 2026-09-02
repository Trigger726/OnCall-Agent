package org.trigger.opspilot.investigation;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class InvestigationControllerDisconnectTest {
    @Test
    void shouldIsolateClientDisconnectFromBackgroundEventPublishing() {
        DisconnectingEmitter emitter = new DisconnectingEmitter();
        AtomicBoolean connected = new AtomicBoolean(true);
        AgentRunEventService.EventSink sink = InvestigationController.emitterSink(emitter, connected);
        AgentRunEventService.EventView event = new AgentRunEventService.EventView(
                1, 7, 1, "RUN_STARTED", null, null, "RUNNING", "{}", LocalDateTime.now());

        assertThatCode(() -> sink.publish(event)).doesNotThrowAnyException();
        assertThat(connected).isFalse();
        assertThat(emitter.sendAttempts).isEqualTo(1);

        assertThatCode(() -> sink.publish(event)).doesNotThrowAnyException();
        assertThat(emitter.sendAttempts).isEqualTo(1);
    }

    private static final class DisconnectingEmitter extends SseEmitter {
        private int sendAttempts;

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            sendAttempts++;
            throw new IOException("simulated client disconnect");
        }
    }
}
