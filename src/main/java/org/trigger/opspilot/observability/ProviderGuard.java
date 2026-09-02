package org.trigger.opspilot.observability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Component
public class ProviderGuard {
    private final int failureThreshold;
    private final long openDurationMillis;
    private final Clock clock;
    private final RetryTemplate retryTemplate;
    private final Map<String, CircuitState> circuits = new ConcurrentHashMap<>();

    @Autowired
    public ProviderGuard(ObservabilityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ProviderGuard(ObservabilityProperties properties, Clock clock) {
        ObservabilityProperties.Reliability reliability = properties.getReliability();
        this.failureThreshold = reliability.getFailureThreshold();
        this.openDurationMillis = reliability.getOpenDuration().toMillis();
        this.clock = clock;
        this.retryTemplate = new RetryTemplate();
        this.retryTemplate.setRetryPolicy(new SimpleRetryPolicy(
                reliability.getMaxAttempts(), Map.of(RuntimeException.class, true)));
        FixedBackOffPolicy backOff = new FixedBackOffPolicy();
        backOff.setBackOffPeriod(Math.max(0, reliability.getBackoff().toMillis()));
        this.retryTemplate.setBackOffPolicy(backOff);
    }

    public <T> T execute(String providerId, Supplier<T> operation) {
        CircuitState circuit = circuits.computeIfAbsent(providerId, ignored -> new CircuitState());
        Instant now = clock.instant();
        synchronized (circuit) {
            if (circuit.openUntil != null && now.isBefore(circuit.openUntil)) {
                throw new ProviderUnavailableException(providerId,
                        "circuit open until " + circuit.openUntil);
            }
            if (circuit.openUntil != null) {
                if (circuit.halfOpenInFlight) {
                    throw new ProviderUnavailableException(providerId, "half-open probe already running");
                }
                circuit.halfOpenInFlight = true;
            }
        }

        try {
            T result = retryTemplate.execute(context -> operation.get());
            synchronized (circuit) {
                circuit.consecutiveFailures = 0;
                circuit.openUntil = null;
                circuit.halfOpenInFlight = false;
            }
            return result;
        } catch (RuntimeException exception) {
            synchronized (circuit) {
                circuit.consecutiveFailures++;
                circuit.halfOpenInFlight = false;
                if (circuit.consecutiveFailures >= failureThreshold) {
                    circuit.openUntil = clock.instant().plusMillis(openDurationMillis);
                }
            }
            throw exception;
        }
    }

    public CircuitSnapshot snapshot(String providerId) {
        CircuitState circuit = circuits.get(providerId);
        if (circuit == null) return new CircuitSnapshot("CLOSED", 0, null);
        synchronized (circuit) {
            Instant now = clock.instant();
            String status = circuit.openUntil == null ? "CLOSED"
                    : now.isBefore(circuit.openUntil) ? "OPEN" : "HALF_OPEN";
            return new CircuitSnapshot(status, circuit.consecutiveFailures, circuit.openUntil);
        }
    }

    private static final class CircuitState {
        private int consecutiveFailures;
        private Instant openUntil;
        private boolean halfOpenInFlight;
    }

    public record CircuitSnapshot(String status, int consecutiveFailures, Instant openUntil) {
    }

    public static class ProviderUnavailableException extends RuntimeException {
        private final String providerId;

        public ProviderUnavailableException(String providerId, String message) {
            super(message);
            this.providerId = providerId;
        }

        public String providerId() {
            return providerId;
        }
    }
}
