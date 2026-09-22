package org.trigger.opspilot.slo;

import java.util.List;
import java.util.Map;

public final class SloBurnRateCalculator {
    private static final List<LaneDefinition> DEFINITIONS = List.of(
            new LaneDefinition("FAST_PAGE", "PAGE", "1h", "5m", 14.4, 2),
            new LaneDefinition("SLOW_PAGE", "PAGE", "6h", "30m", 6, 5),
            new LaneDefinition("TICKET", "TICKET", "3d", "6h", 1, 10));
    private static final List<String> REQUIRED_WINDOWS = List.of("5m", "30m", "1h", "6h", "3d");

    private SloBurnRateCalculator() {
    }

    public static List<String> requiredWindows() {
        return REQUIRED_WINDOWS;
    }

    public static Assessment assess(double targetPercent, Map<String, WindowSample> samples) {
        double budgetFraction = (100 - targetPercent) / 100;
        List<Lane> lanes = DEFINITIONS.stream()
                .map(definition -> evaluate(definition, budgetFraction, samples))
                .toList();
        Lane firing = lanes.stream().filter(lane -> "FIRING".equals(lane.status())).findFirst().orElse(null);
        if (firing != null) {
            String status = switch (firing.id()) {
                case "FAST_PAGE" -> "PAGE_FAST";
                case "SLOW_PAGE" -> "PAGE_SLOW";
                default -> "TICKET";
            };
            return new Assessment(status, firing.severity(),
                    firing.id() + " 长短窗口均超过 " + firing.threshold() + "x", lanes);
        }
        if (lanes.stream().allMatch(lane -> "OK".equals(lane.status()))) {
            return new Assessment("HEALTHY", "NONE", "三档长短窗口均未同时超阈值", lanes);
        }
        if (hasStatus(lanes, "PROVIDER_ERROR")) {
            return new Assessment("PROVIDER_ERROR", "UNKNOWN", "Prometheus 窗口查询失败", lanes);
        }
        if (hasStatus(lanes, "INVALID_DATA")) {
            return new Assessment("INVALID_DATA", "UNKNOWN", "窗口计数自相矛盾", lanes);
        }
        return new Assessment("NO_DATA", "UNKNOWN", "至少一档缺少有效正分母", lanes);
    }

    public static Assessment unavailable(String status, String message) {
        return new Assessment(status, "UNKNOWN", message, List.of());
    }

    private static boolean hasStatus(List<Lane> lanes, String status) {
        return lanes.stream().anyMatch(lane -> status.equals(lane.status()));
    }

    private static Lane evaluate(LaneDefinition definition, double budgetFraction,
                                 Map<String, WindowSample> samples) {
        WindowSample longSample = samples.get(definition.longWindow());
        WindowSample shortSample = samples.get(definition.shortWindow());
        String invalidStatus = invalidStatus(longSample, shortSample);
        if (invalidStatus != null) {
            return new Lane(definition.id(), definition.severity(), definition.longWindow(),
                    definition.shortWindow(), definition.threshold(), definition.budgetConsumedPercent(),
                    invalidStatus, null, null, total(longSample), total(shortSample),
                    message(longSample, shortSample));
        }
        double longBurnRate = burnRate(longSample, budgetFraction);
        double shortBurnRate = burnRate(shortSample, budgetFraction);
        boolean firing = longBurnRate >= definition.threshold() && shortBurnRate >= definition.threshold();
        return new Lane(definition.id(), definition.severity(), definition.longWindow(),
                definition.shortWindow(), definition.threshold(), definition.budgetConsumedPercent(),
                firing ? "FIRING" : "OK", round(longBurnRate), round(shortBurnRate),
                longSample.totalEvents(), shortSample.totalEvents(), null);
    }

    private static String invalidStatus(WindowSample first, WindowSample second) {
        if (first == null || second == null) return "NO_DATA";
        if ("PROVIDER_ERROR".equals(first.status()) || "PROVIDER_ERROR".equals(second.status())) {
            return "PROVIDER_ERROR";
        }
        if ("INVALID_DATA".equals(first.status()) || "INVALID_DATA".equals(second.status())) {
            return "INVALID_DATA";
        }
        if (!"VALID".equals(first.status()) || !"VALID".equals(second.status())) return "NO_DATA";
        return null;
    }

    private static String message(WindowSample first, WindowSample second) {
        if (first != null && first.message() != null) return first.message();
        return second == null ? null : second.message();
    }

    private static Double total(WindowSample sample) {
        return sample == null ? null : sample.totalEvents();
    }

    private static double burnRate(WindowSample sample, double budgetFraction) {
        double errorRate = (sample.totalEvents() - sample.goodEvents()) / sample.totalEvents();
        return errorRate / budgetFraction;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private record LaneDefinition(String id, String severity, String longWindow, String shortWindow,
                                  double threshold, double budgetConsumedPercent) {
    }

    public record WindowSample(String status, Double goodEvents, Double totalEvents, String message) {
    }

    public record Lane(String id, String severity, String longWindow, String shortWindow,
                       double threshold, double budgetConsumedPercent, String status,
                       Double longBurnRate, Double shortBurnRate,
                       Double longTotalEvents, Double shortTotalEvents, String message) {
    }

    public record Assessment(String status, String severity, String message, List<Lane> lanes) {
    }
}
