package org.trigger.opspilot.assistant;

import org.springframework.http.HttpStatus;
import org.trigger.opspilot.common.ApiException;

import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Preserve native text while withholding an incomplete/unknown retrieved reference. */
final class AssistantCitationGuard implements Consumer<String> {
    private static final Set<String> PREFIXES = Set.of("runbook:", "metric:", "log:");
    private static final Pattern PREFIX_PATTERN = Pattern.compile("runbook:|metric:|log:", Pattern.CASE_INSENSITIVE);
    private final Set<String> allowed;
    private final Consumer<String> output;
    private final StringBuilder pending = new StringBuilder();

    AssistantCitationGuard(Set<String> allowed, Consumer<String> output) {
        this.allowed = Set.copyOf(allowed);
        this.output = output;
    }

    static void validate(String content, Set<String> allowed) {
        var guard = new AssistantCitationGuard(allowed, ignored -> {});
        guard.accept(content);
        guard.finish();
    }

    @Override public void accept(String chunk) {
        pending.append(chunk);
        flush(false);
    }

    void finish() { flush(true); }

    private void flush(boolean complete) {
        while (!pending.isEmpty()) {
            String lower = pending.toString().toLowerCase(Locale.ROOT);
            var match = PREFIX_PATTERN.matcher(pending);
            int start = match.find() ? match.start() : -1;
            if (start < 0) {
                int keep = 0;
                if (!complete) {
                    for (String prefix : PREFIXES) for (int size = 1; size < prefix.length() && size <= lower.length(); size++) {
                        if (lower.endsWith(prefix.substring(0, size))) keep = Math.max(keep, size);
                    }
                }
                emit(pending.length() - keep);
                return;
            }
            int prefixLength = match.end() - match.start();
            if (start > 0) emit(start);
            int end = prefixLength;
            while (end < pending.length() && referenceCharacter(pending.charAt(end))) end++;
            String reference = pending.substring(0, end);
            if (end == pending.length() && !complete && allowed.stream().anyMatch(item -> item.startsWith(reference))) return;
            if (!allowed.contains(reference)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "ASSISTANT_CITATION_INVALID",
                        "模型引用不在本次检索证据中，回答未保存");
            }
            emit(end);
        }
    }

    private static boolean referenceCharacter(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9' || "-_:#/".indexOf(value) >= 0;
    }

    private void emit(int length) {
        if (length == 0) return;
        String text = pending.substring(0, length);
        pending.delete(0, length);
        output.accept(text);
    }
}
