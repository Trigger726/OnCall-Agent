package org.trigger.opspilot.investigation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentEventReceiverTest {
    private static final String KEY = "receiver-unit-test";
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> operations = mock(StreamOperations.class);
    private final List<Object> received = new ArrayList<>();
    private final AgentEventReceiver receiver = new AgentEventReceiver(redis, received::add, KEY);

    AgentEventReceiverTest() { when(redis.opsForStream()).thenReturn(operations); }

    @ParameterizedTest
    @CsvSource({
            "10-0, 9-0",
            "10-10, 10-9",
            "9223372036854775808-0, 9223372036854775807-0",
            "10-18446744073709551615, 10-9223372036854775808",
            "10-0, 10-0"
    })
    void shouldRewindOnRegressedOrReusedTailIdentity(String oldId, String newId) {
        var old = record(oldId, 1);
        var replacement = record(newId, 2);
        when(operations.read(any(StreamReadOptions.class), eq(offset("0-0"))))
                .thenReturn(List.of(old), List.of(replacement));
        when(operations.read(any(StreamReadOptions.class), eq(offset(oldId)))).thenReturn(List.of());
        when(operations.reverseRange(eq(KEY), any(Range.class), any(Limit.class)))
                .thenReturn(List.of(replacement));

        receiver.poll();
        receiver.poll(); // An idle poll detects replacement, without an unbounded reread.
        assertThat(received).containsExactly(new AgentEventReceiver.Notification(1, 1));
        receiver.poll();
        assertThat(received).containsExactly(new AgentEventReceiver.Notification(1, 1),
                new AgentEventReceiver.Notification(1, 2));
    }

    @Test
    void shouldRewindAfterObservingEmptyOrMissingStream() {
        when(operations.read(any(StreamReadOptions.class), eq(offset("0-0"))))
                .thenReturn(List.of(record("10-0", 1)), List.of(record("1-0", 2)));
        when(operations.read(any(StreamReadOptions.class), eq(offset("10-0")))).thenReturn(List.of());
        when(operations.reverseRange(eq(KEY), any(Range.class), any(Limit.class))).thenReturn(List.of());
        receiver.poll();
        receiver.poll();
        receiver.poll();
        assertThat(received).containsExactly(new AgentEventReceiver.Notification(1, 1),
                new AgentEventReceiver.Notification(1, 2));
    }

    @Test
    void shouldNotRewindUnchangedTailOrCheckTailDuringActiveReads() {
        var old = record("10-0", 1);
        when(operations.read(any(StreamReadOptions.class), eq(offset("0-0")))).thenReturn(List.of(old));
        when(operations.read(any(StreamReadOptions.class), eq(offset("10-0")))).thenReturn(List.of());
        when(operations.reverseRange(eq(KEY), any(Range.class), any(Limit.class))).thenReturn(List.of(old));
        receiver.poll();
        verify(operations, never()).reverseRange(anyString(), any(Range.class), any(Limit.class));
        receiver.poll();
        receiver.poll();
        verify(operations, times(1)).read(any(StreamReadOptions.class), eq(offset("0-0")));
        assertThat(received).hasSize(1);
    }

    @Test
    void shouldPreserveCursorWhenTailInspectionFails() {
        when(operations.read(any(StreamReadOptions.class), eq(offset("0-0"))))
                .thenReturn(List.of(record("10-0", 1)));
        when(operations.read(any(StreamReadOptions.class), eq(offset("10-0"))))
                .thenReturn(List.of(), List.of(record("11-0", 2)));
        when(operations.reverseRange(eq(KEY), any(Range.class), any(Limit.class)))
                .thenThrow(new IllegalStateException("Redis temporarily unavailable"));
        receiver.poll();
        receiver.poll();
        receiver.poll();
        assertThat(received).containsExactly(new AgentEventReceiver.Notification(1, 1),
                new AgentEventReceiver.Notification(1, 2));
        verify(operations, times(1)).read(any(StreamReadOptions.class), eq(offset("0-0")));
    }

    private static StreamOffset<String> offset(String id) {
        return StreamOffset.create(KEY, ReadOffset.from(id));
    }

    private static MapRecord<String, Object, Object> record(String id, long eventId) {
        return MapRecord.create(KEY, Map.<Object, Object>of(
                "schemaVersion", "1", "runId", "1", "eventId", Long.toString(eventId)))
                .withId(RecordId.of(id));
    }
}
