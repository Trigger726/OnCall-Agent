package org.trigger.opspilot.observability.metrics;

import org.springframework.stereotype.Service;
import org.trigger.opspilot.observability.ProviderGuard.ProviderUnavailableException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class MetricsProviderRouter {
    private final List<MetricsProvider> providers;

    public MetricsProviderRouter(List<MetricsProvider> providers) {
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(MetricsProvider::priority).reversed()).toList();
    }

    public MetricsProvider.MetricsResult query(MetricsProvider.MetricsQuery query) {
        List<String> warnings = new ArrayList<>();
        for (MetricsProvider provider : providers) {
            if (!provider.available()) continue;
            try {
                MetricsProvider.MetricsResult result = provider.query(query);
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
