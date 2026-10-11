package org.trigger.opspilot.assistant;

import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.stereotype.Service;
import org.trigger.opspilot.incident.IncidentService;
import org.trigger.opspilot.common.ApiException;
import org.trigger.opspilot.observability.LogRedactor;
import org.trigger.opspilot.observability.ObservationQueryContextFactory;
import org.trigger.opspilot.observability.metrics.MetricsProvider;
import org.trigger.opspilot.observability.metrics.MetricsProviderRouter;
import org.trigger.opspilot.observability.logs.LogsProvider;
import org.trigger.opspilot.observability.logs.LogsProviderRouter;
import org.trigger.opspilot.security.SessionAuthorization;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Fixed, incident-scoped read plan; user/model text can never supply a query expression. */
@Service
public class AssistantObservationService {
    private static final int LIMIT = 20;
    private static final Pattern RANGE = Pattern.compile("(?i)(?:最近|近|过去|last|past)\\s*(\\d+)\\s*(分钟|小时|天|minutes?|hours?|days?)");
    private static final Pattern RECENT_ENGLISH = Pattern.compile("\\b(?:latest|now|current|recent|last|past)\\b");
    private final IncidentService incidents;
    private final ObservationQueryContextFactory contexts;
    private final MetricsProviderRouter metrics;
    private final LogsProviderRouter logs;
    private final LogRedactor redactor;
    private final SessionAuthorization authorization;

    public AssistantObservationService(IncidentService incidents, ObservationQueryContextFactory contexts,
                                       MetricsProviderRouter metrics, LogsProviderRouter logs,
                                       LogRedactor redactor, SessionAuthorization authorization) {
        this.incidents = incidents; this.contexts = contexts; this.metrics = metrics; this.logs = logs;
        this.redactor = redactor; this.authorization = authorization;
    }

    public Snapshot collect(Long incidentId, String question, SessionAuthorization.Lease lease, Runnable checkpoint) {
        String q = question.toLowerCase(Locale.ROOT);
        boolean wantMetrics = contains(q, "指标", "metric", "cpu", "内存", "latency");
        boolean wantLogs = contains(q, "日志", "logs", "log ", "log查询");
        if (!wantMetrics && !wantLogs) return new Snapshot("NOT_REQUESTED", false, List.of());
        checkpoint.run();
        if (!authorization.authorized(lease, false)) throw new CredentialsExpiredException("Observation session expired");
        if (!authorization.authorized(lease, true)) return new Snapshot("FORBIDDEN", false, List.of());
        if (incidentId == null) return new Snapshot("INCIDENT_REQUIRED", false, List.of());
        var range = RANGE.matcher(q);
        while (range.find()) {
            if (!"30".equals(range.group(1)) || !(range.group(2).equals("分钟") || range.group(2).startsWith("minute"))) {
                return new Snapshot("TIME_RANGE_UNSUPPORTED", false, List.of());
            }
        }
        if (q.matches("(?s).*(昨天|今天|前天|\\d{4}-\\d{2}-\\d{2}).*")) return new Snapshot("TIME_RANGE_UNSUPPORTED", false, List.of());
        var context = contexts.from(incidents.get(incidentId));
        boolean recent = !q.contains("故障窗口") && (contains(q, "最新", "现在", "当前", "实时", "最近", "近30", "近 30", "过去")
                || RECENT_ENGLISH.matcher(q).find());
        LocalDateTime end = recent ? LocalDateTime.now() : context.end();
        LocalDateTime start = recent ? end.minusMinutes(30) : context.start();
        String window = recent ? "RECENT_30_MINUTES" : "INCIDENT_WINDOW";
        var channels = new ArrayList<Channel>();
        if (wantMetrics) {
            fence(lease, checkpoint);
            Channel channel;
            try {
                var result = metrics.query(new MetricsProvider.MetricsQuery(context.incidentId(), context.resourceId(),
                        context.resourceCode(), context.resourceName(), start, end), () -> fence(lease, checkpoint));
                var samples = result.samples().stream().filter(s -> within(s.observedAt(), start, end)).limit(LIMIT).toList();
                var evidence = samples.stream().map(s -> new AssistantService.EvidenceRef("metric:" + result.providerId() + ":" + s.ref(),
                        "METRIC", safe(s.metricName()), new EvidenceSnapshot(safe(result.providerId()), window, start, end,
                        s.observedAt(), safe(s.resourceCode()), safe(s.metricName() + "=" + s.value() + " " + s.unit()
                        + "; labels=" + redactor.redact(s.labels()))))).toList();
                var warnings = new ArrayList<>(result.warnings());
                if (samples.size() < result.samples().size()) warnings.add("样本超过 20 项或时间不在请求窗口，已截断/排除；不是总体计数");
                if ("prometheus-metrics".equals(result.providerId())) warnings.add("Prometheus 使用窗口末端 instant query，非全窗口时序/趋势统计");
                channel = channel("METRIC", result.providerId(), result.query(), context.resourceCode(), window, start, end, warnings, evidence);
            } catch (CredentialsExpiredException | ApiException stop) {
                throw stop;
            } catch (RuntimeException error) {
                channel = failed("METRIC", context.resourceCode(), window, start, end, error);
            }
            fence(lease, checkpoint); // Never let a caught provider failure swallow stop/revocation.
            channels.add(channel);
        }
        if (wantLogs) {
            fence(lease, checkpoint);
            Channel channel;
            try {
                var result = logs.query(new LogsProvider.LogsQuery(context.incidentId(), context.resourceId(),
                        context.resourceCode(), context.resourceName(), start, end, LIMIT), () -> fence(lease, checkpoint));
                var entries = result.entries().stream().filter(e -> within(e.occurredAt(), start, end)).limit(LIMIT).toList();
                var evidence = entries.stream().map(e -> new AssistantService.EvidenceRef("log:" + result.providerId() + ":" + e.ref(),
                        "LOG", safe(e.level() + " · " + e.loggerName()), new EvidenceSnapshot(safe(result.providerId()), window, start, end,
                        e.occurredAt(), safe(e.resourceCode()), safe("[" + e.level() + "] " + e.loggerName() + "; trace=" + e.traceId()
                        + "; " + e.message() + "; metadata=" + redactor.redact(e.metadata()))))).toList();
                var warnings = new ArrayList<>(result.warnings());
                warnings.add("最多 20 条脱敏样本，非日志总体计数；Provider 脱敏字段=" + result.redactedFields());
                if (entries.size() < result.entries().size()) warnings.add("部分日志超过上限或时间不在请求窗口，已排除");
                channel = channel("LOG", result.providerId(), result.query(), context.resourceCode(), window, start, end, warnings, evidence);
            } catch (CredentialsExpiredException | ApiException stop) {
                throw stop;
            } catch (RuntimeException error) {
                channel = failed("LOG", context.resourceCode(), window, start, end, error);
            }
            fence(lease, checkpoint);
            channels.add(channel);
        }
        return new Snapshot("QUERIED", true, List.copyOf(channels));
    }

    public void fence(SessionAuthorization.Lease lease, Runnable checkpoint, Snapshot snapshot) {
        if (snapshot.requiresPrivilege()) fence(lease, checkpoint);
    }

    private void fence(SessionAuthorization.Lease lease, Runnable checkpoint) {
        checkpoint.run();
        if (!authorization.authorized(lease, true)) throw new CredentialsExpiredException("Observation permission expired");
    }

    private Channel channel(String type, String provider, String query, String resource, String window,
                            LocalDateTime start, LocalDateTime end, List<String> warnings, List<AssistantService.EvidenceRef> evidence) {
        return new Channel(type, evidence.isEmpty() ? "EMPTY" : "OK", safe(provider), safe(query), safe(resource),
                window, start, end, warnings.stream().map(this::safe).toList(), evidence);
    }

    private Channel failed(String type, String resource, String window, LocalDateTime start, LocalDateTime end, RuntimeException error) {
        // Error bodies/URLs may contain credentials. Only record the exception class, not getMessage().
        return new Channel(type, "FAILED", null, null, safe(resource), window, start, end,
                List.of("Provider 查询失败 (" + error.getClass().getSimpleName() + ")；不能解释为无数据"), List.of());
    }

    private String safe(String text) {
        String value = redactor.redact(text);
        if (value == null || value.length() <= 600) return value;
        int end = Character.isHighSurrogate(value.charAt(599)) ? 599 : 600;
        return value.substring(0, end) + "…[截断]";
    }

    private static boolean within(LocalDateTime at, LocalDateTime start, LocalDateTime end) {
        return at != null && !at.isBefore(start) && !at.isAfter(end);
    }

    private static boolean contains(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    public record EvidenceSnapshot(String provider, String window, LocalDateTime windowStart, LocalDateTime windowEnd,
                                   LocalDateTime observedAt, String resourceCode, String text) { }
    public record Channel(String type, String status, String provider, String query, String resourceCode, String window,
                          LocalDateTime start, LocalDateTime end, List<String> warnings, List<AssistantService.EvidenceRef> evidence) { }
    public record Snapshot(String status, boolean requiresPrivilege, List<Channel> channels) {
        public List<AssistantService.EvidenceRef> evidence() {
            return channels.stream().flatMap(c -> c.evidence().stream()).toList();
        }
        public String summary() {
            if ("NOT_REQUESTED".equals(status)) return "";
            StringBuilder text = new StringBuilder("\n\n### 只读指标 / 日志取证\n");
            if (!"QUERIED".equals(status)) {
                return text.append(switch (status) {
                    case "FORBIDDEN" -> "当前角色无运维取证权限，未查询 Provider。";
                    case "INCIDENT_REQUIRED" -> "未绑定 Incident，未查询 Provider；请先绑定事件确定资源范围。";
                    default -> "该显式时间范围暂不支持，未查询 Provider；目前仅支持 Incident 故障窗口或最新近 30 分钟。";
                }).toString();
            }
            for (var channel : channels) {
                text.append("\n").append(channel.type()).append(" · ").append(channel.status())
                        .append("；来源：").append(channel.provider() == null ? "不可用" : channel.provider())
                        .append("；资源：").append(channel.resourceCode()).append("\n")
                        .append("时间窗：").append(channel.window().equals("RECENT_30_MINUTES") ? "最新近 30 分钟" : "Incident 故障窗口（不是实时）")
                        .append(" [").append(channel.start()).append(" ～ ").append(channel.end()).append("]\n");
                if ("EMPTY".equals(channel.status())) text.append("该窗口未返回样本，不代表系统健康或没有故障。\n");
                for (var ref : channel.evidence()) text.append("- [").append(ref.ref()).append("] ")
                        .append(ref.snapshot().observedAt()).append(" · ").append(ref.snapshot().resourceCode()).append(" · ")
                        .append(ref.snapshot().text()).append("\n");
                channel.warnings().forEach(w -> text.append("提示：").append(w).append("\n"));
            }
            return text.append("仅为只读、有限样本；未执行修复，也未确认根因。").toString();
        }
    }
}
