package org.trigger.opspilot.observability.logs;

import org.springframework.stereotype.Service;
import org.trigger.opspilot.observability.ProviderGuard.ProviderUnavailableException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class LogsProviderRouter {
    private final List<LogsProvider> providers;

    public LogsProviderRouter(List<LogsProvider> providers) {
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(LogsProvider::priority).reversed()).toList();
    }

    public LogsProvider.LogsResult query(LogsProvider.LogsQuery query) {
        List<String> warnings = new ArrayList<>();
        for (LogsProvider provider : providers) {
            if (!provider.available()) continue;
            try {
                LogsProvider.LogsResult result = provider.query(query);
                List<String> combined = new ArrayList<>(warnings);
                combined.addAll(result.warnings());
                return result.withWarnings(List.copyOf(combined));
            } catch (RuntimeException exception) {
                warnings.add(provider.id() + " unavailable (" + exception.getClass().getSimpleName() + ")");
            }
        }
        throw new ProviderUnavailableException("logs-router",
                warnings.isEmpty() ? "no enabled logs provider" : String.join("; ", warnings));
    }

    public List<LogsProvider> providers() {
        return providers;
    }
}
