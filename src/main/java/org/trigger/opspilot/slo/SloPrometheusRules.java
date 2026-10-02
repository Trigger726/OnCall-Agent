package org.trigger.opspilot.slo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic rule compilation. Deployment and native PromQL validation remain explicit steps. */
public final class SloPrometheusRules {
    public static final String POLICY_VERSION = "budget-period-v1";
    public static final int EVALUATION_SECONDS = 30;
    public static final int FRESHNESS_SECONDS = 60;

    private SloPrometheusRules() { }

    public static Bundle compile(ServiceSloService.ObjectiveView objective, ObjectMapper mapper) {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (String window : SloBurnRateCalculator.requiredWindows(objective.windowDays())) {
            Map<String, String> labels = labels(objective);
            labels.put("window", window);
            String good = metric(objective, "good", window);
            String total = metric(objective, "total", window);
            rules.add(record(good, ServiceSloService.render(objective.goodEventsQueryTemplate(),
                    objective.serviceCode(), window), labels));
            rules.add(record(total, ServiceSloService.render(objective.totalEventsQueryTemplate(),
                    objective.serviceCode(), window), labels));
            // scalar() rejects empty/multiple series as NaN; finite checks also reject +/-Inf.
            String g = "scalar(" + good + ")";
            String t = "scalar(" + total + ")";
            double budget = (100 - objective.targetPercent()) / 100;
            String burn = "vector((" + t + " - " + g + ") / " + t + " / " + budget + ")"
                    + " and on() (vector(" + t + ") > 0)"
                    + " and on() (vector(" + g + ") >= 0)"
                    + " and on() (vector(" + g + ") <= " + t + ")"
                    + " and on() (vector(" + g + " - " + g + ") == 0)"
                    + " and on() (vector(" + t + " - " + t + ") == 0)"
                    + " and on() (vector(time() - scalar(timestamp(" + good + "))) <= " + FRESHNESS_SECONDS + ")"
                    + " and on() (vector(time() - scalar(timestamp(" + total + "))) <= " + FRESHNESS_SECONDS + ")";
            rules.add(record(metric(objective, "burn", window), burn, labels));
        }
        List<SloBurnRateCalculator.LaneDefinition> lanes = SloBurnRateCalculator.definitions(objective.windowDays());
        List<String> higherPriority = new ArrayList<>();
        for (int i = 0; i < lanes.size(); i++) {
            var lane = lanes.get(i);
            String expression = firing(objective, lane);
            if (!higherPriority.isEmpty()) {
                expression += " unless on() (" + String.join(" or on() ", higherPriority) + ")";
            }
            Map<String, String> labels = labels(objective);
            labels.put("lane", lane.id());
            labels.put("severity", "P" + (i + 1));
            rules.add(alert(List.of("OpsPilotSloFastPage", "OpsPilotSloSlowPage", "OpsPilotSloTicket").get(i),
                    expression, labels, objective.serviceCode() + " SLO " + lane.id()));
            higherPriority.add("(" + firing(objective, lane) + ")");
        }
        for (String window : SloBurnRateCalculator.requiredWindows(objective.windowDays())) {
            Map<String, String> labels = labels(objective);
            labels.put("window", window);
            labels.put("severity", "P3");
            String burn = metric(objective, "burn", window);
            rules.add(alert("OpsPilotSloDataUnavailable", "absent(" + burn + ") or (time() - timestamp("
                    + burn + ") > " + FRESHNESS_SECONDS + ")", labels,
                    objective.serviceCode() + " SLO " + window + " 数据不可用"));
        }
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("name", "opspilot-slo-" + objective.id() + "-v" + objective.version());
        group.put("interval", EVALUATION_SECONDS + "s");
        group.put("rules", rules);
        try {
            // JSON is a YAML subset; use the existing serializer to escape all user-supplied strings.
            String yaml = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("groups", List.of(group)))
                    .replace("\r\n", "\n") + "\n";
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(yaml.getBytes(StandardCharsets.UTF_8)));
            return new Bundle(objective.id(), objective.version(), objective.serviceCode(), objective.targetPercent(),
                    objective.windowDays(), POLICY_VERSION, rules.size(), digest, yaml);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Cannot serialize SLO rules", exception);
        }
    }

    private static String firing(ServiceSloService.ObjectiveView objective, SloBurnRateCalculator.LaneDefinition lane) {
        return "(" + freshBurn(objective, lane.longWindow()) + " >= " + lane.threshold() + ") and on() ("
                + freshBurn(objective, lane.shortWindow()) + " >= " + lane.threshold() + ")";
    }

    private static String freshBurn(ServiceSloService.ObjectiveView objective, String window) {
        String metric = metric(objective, "burn", window);
        return "(" + metric + " and (time() - timestamp(" + metric + ") <= " + FRESHNESS_SECONDS + "))";
    }

    private static String metric(ServiceSloService.ObjectiveView objective, String kind, String window) {
        return "opspilot:slo_" + objective.id() + "_v" + objective.version() + ":" + kind + "_" + window;
    }

    private static Map<String, String> labels(ServiceSloService.ObjectiveView objective) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("resource_code", objective.serviceCode());
        labels.put("slo_id", Long.toString(objective.id()));
        labels.put("slo_version", Integer.toString(objective.version()));
        return labels;
    }

    private static Map<String, Object> record(String name, String expr, Map<String, String> labels) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("record", name);
        rule.put("expr", expr);
        rule.put("labels", labels);
        return rule;
    }

    private static Map<String, Object> alert(String name, String expr, Map<String, String> labels, String summary) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("alert", name);
        rule.put("expr", expr);
        rule.put("labels", labels);
        rule.put("annotations", Map.of("summary", summary));
        return rule;
    }

    public record Bundle(long objectiveId, int objectiveVersion, String serviceCode, double targetPercent,
                         int windowDays, String policyVersion, int ruleCount, String sha256, String rulesYaml) { }
}
