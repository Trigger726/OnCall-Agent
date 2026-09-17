package org.trigger.opspilot.observability.metrics;

import org.springframework.stereotype.Service;
import org.trigger.opspilot.observability.ProviderGuard.ProviderUnavailableException;
import org.trigger.opspilot.observability.tracing.OpsPilotTracing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class MetricsProviderRouter {
    private final List<MetricsProvider> providers;
    private final OpsPilotTracing tracing;

    public MetricsProviderRouter(List<MetricsProvider> providers, OpsPilotTracing tracing) {
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(MetricsProvider::priority).reversed()).toList();
        this.tracing = tracing;
    }

    public MetricsProvider.MetricsResult query(MetricsProvider.MetricsQuery query) {
        List<String> warnings = new ArrayList<>();
        for (MetricsProvider provider : providers) {
            if (!provider.available()) continue;
            try {
                MetricsProvider.MetricsResult result = tracing.traceProviderQuery(
                        "metrics", provider.id(), () -> provider.query(query));
                List<String> combined = new ArrayList<>(warnings);
                combined.addAll(result.warnings());
                return result.withWarnings(List.copyOf(combined));
            } catch (RuntimeException exception) {
                warnings.add(provider.id() + " unavailable (" + exception.getClass().getSimpleName() + ")");
            }
        }
        throw new ProviderUnavailableException("metrics-router",
                warnings.isEmpty() ? "no enabled metrics provider" : String.join("; ", warnings));
    }

    public List<MetricsProvider> providers() {
        return providers;
    }
}
