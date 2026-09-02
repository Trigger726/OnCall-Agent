package org.trigger.opspilot.observability;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderGuardTest {
    @Test
    void shouldRetryOpenCircuitAndRecoverThroughHalfOpenProbe() {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getReliability().setMaxAttempts(2);
        properties.getReliability().setBackoff(Duration.ZERO);
        properties.getReliability().setFailureThreshold(2);
        properties.getReliability().setOpenDuration(Duration.ofSeconds(10));
        MutableClock clock = new MutableClock(Instant.parse("2026-08-31T00:00:00Z"));
        ProviderGuard guard = new ProviderGuard(properties, clock);
        AtomicInteger attempts = new AtomicInteger();

        for (int request = 0; request < 2; request++) {
            assertThatThrownBy(() -> guard.execute("prometheus", () -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("upstream unavailable");
            })).isInstanceOf(IllegalStateException.class);
        }

        assertThat(attempts).hasValue(4);
        assertThat(guard.snapshot("prometheus").status()).isEqualTo("OPEN");
        assertThatThrownBy(() -> guard.execute("prometheus", () -> "not-called"))
                .isInstanceOf(ProviderGuard.ProviderUnavailableException.class);

        clock.advance(Duration.ofSeconds(11));
        assertThat(guard.snapshot("prometheus").status()).isEqualTo("HALF_OPEN");
        assertThat(guard.execute("prometheus", () -> "healthy")).isEqualTo("healthy");
        assertThat(guard.snapshot("prometheus"))
                .extracting(ProviderGuard.CircuitSnapshot::status,
                        ProviderGuard.CircuitSnapshot::consecutiveFailures)
                .containsExactly("CLOSED", 0);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
