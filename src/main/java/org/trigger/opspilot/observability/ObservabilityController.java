package org.trigger.opspilot.observability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.trigger.opspilot.common.ApiResponse;
import org.trigger.opspilot.observability.logs.LogsProviderRouter;
import org.trigger.opspilot.observability.metrics.MetricsProviderRouter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/observability")
public class ObservabilityController {
    private final MetricsProviderRouter metricsRouter;
    private final LogsProviderRouter logsRouter;
    private final ProviderGuard providerGuard;

    public ObservabilityController(MetricsProviderRouter metricsRouter,
                                   LogsProviderRouter logsRouter,
                                   ProviderGuard providerGuard) {
        this.metricsRouter = metricsRouter;
        this.logsRouter = logsRouter;
        this.providerGuard = providerGuard;
    }

    @GetMapping("/providers")
    public ApiResponse<List<ProviderView>> providers() {
        List<ProviderView> views = new ArrayList<>();
        metricsRouter.providers().forEach(provider -> views.add(view(
                provider.id(), "METRICS", provider.priority(), provider.available())));
        logsRouter.providers().forEach(provider -> views.add(view(
                provider.id(), "LOGS", provider.priority(), provider.available())));
        return ApiResponse.ok(List.copyOf(views));
    }

    private ProviderView view(String id, String type, int priority, boolean available) {
        ProviderGuard.CircuitSnapshot circuit = providerGuard.snapshot(id);
        return new ProviderView(id, type, priority, available, circuit.status(),
                circuit.consecutiveFailures(), circuit.openUntil());
    }

    public record ProviderView(String id, String type, int priority, boolean available,
                               String circuitState, int consecutiveFailures, Instant openUntil) {
    }
}
