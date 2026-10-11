package org.trigger.opspilot.assistant;

import org.junit.jupiter.api.Test;
import org.trigger.opspilot.common.ApiException;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class AssistantCitationGuardTest {
    private static final String REF = "runbook:redis-pool:v1#chunk-0";
    private static final Set<String> ALLOWED = Set.of(REF, "runbook:mysql-pool:v2#chunk-1");

    @Test void allChunkBoundariesShouldPreserveExactValidTextIncludingUnicodeAndMarkdown() {
        String content = "İ 证据🙂\n[" + REF + "]，参见 `runbook:mysql-pool:v2#chunk-1`。\n后续 r";
        for (int split = 0; split <= content.length(); split++) {
            var output = new StringBuilder();
            var guard = new AssistantCitationGuard(ALLOWED, output::append);
            guard.accept(content.substring(0, split)); guard.accept(content.substring(split)); guard.finish();
            assertThat(output.toString()).as("split %s", split).isEqualTo(content);
        }
    }

    @Test void shouldHoldReferenceUntilBoundaryOrNormalCompletion() {
        var output = new StringBuilder(); var guard = new AssistantCitationGuard(ALLOWED, output::append);
        guard.accept("事实：run"); assertThat(output.toString()).isEqualTo("事实：");
        guard.accept("book:redis-pool:v1#chunk-0"); assertThat(output.toString()).isEqualTo("事实：");
        guard.finish(); assertThat(output.toString()).isEqualTo("事实：" + REF);
    }

    @Test void completePrefixWithoutAvailableEvidenceShouldFailWithoutLeakingReference() {
        var output = new StringBuilder(); var guard = new AssistantCitationGuard(Set.of(), output::append);
        assertThatThrownBy(() -> guard.accept("事实：runbook:"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("ASSISTANT_CITATION_INVALID"));
        assertThat(output.toString()).isEqualTo("事实：");
    }

    @Test void unknownDocumentVersionChunkMalformedCaseOrSuffixShouldNeverBeEmitted() {
        for (String bad : new String[]{"runbook:phantom:v1#chunk-0", "runbook:redis-pool:v9#chunk-0",
                "runbook:redis-pool:v1#chunk-9", "runbook:redis-pool", "runbook:redis-pool:v1#chunk-0evil",
                "RUNBOOK:redis-pool:v1#chunk-0", REF + "/evil", REF + "_evil", "runbook:"}) {
            for (int split = 0; split <= bad.length(); split++) {
                var output = new StringBuilder(); var guard = new AssistantCitationGuard(ALLOWED, output::append);
                int point = split;
                assertThatThrownBy(() -> { guard.accept("事实：" + bad.substring(0, point)); guard.accept(bad.substring(point)); guard.finish(); })
                        .as("%s split %s", bad, split).isInstanceOf(ApiException.class);
                assertThat(output.toString()).isEqualTo("事实：");
            }
        }
    }

    @Test void shouldAllowNoReferencesAndFlushPartialOrdinaryWordAtEof() {
        for (var content : new String[]{"", "普通回答，无文档引用", "request runner", "run", "runboo", " < >\n "}) {
            var output = new StringBuilder(); var guard = new AssistantCitationGuard(Set.of(), output::append);
            content.chars().forEach(c -> guard.accept(String.valueOf((char) c))); guard.finish();
            assertThat(output.toString()).isEqualTo(content);
        }
    }

    @Test void validationShouldRejectInvalidReferenceAfterAnAlreadyValidOne() {
        assertThatThrownBy(() -> AssistantCitationGuard.validate(REF + "\nrunbook:phantom:v1#chunk-0", ALLOWED)).isInstanceOf(ApiException.class);
        AssistantCitationGuard.validate("[" + REF + "].", ALLOWED);
    }

    @Test void metricAndLogPrefixesShouldPreserveEverySplitWithoutAcceptingWrongSourceOrSuffix() {
        var refs = Set.of("metric:prometheus-metrics:0", "log:loki-logs:0:0:123456");
        String content = "İ [metric:prometheus-metrics:0]，[log:loki-logs:0:0:123456]🙂";
        for (int split = 0; split <= content.length(); split++) {
            var output = new StringBuilder(); var guard = new AssistantCitationGuard(refs, output::append);
            guard.accept(content.substring(0, split)); guard.accept(content.substring(split)); guard.finish();
            assertThat(output.toString()).isEqualTo(content);
        }
        for (String bad : new String[]{"metric:local-metrics:0", "metric:prometheus-metrics:0evil", "log:loki-logs:0:0:123457", "LOG:loki-logs:0:0:123456", "metric:", "log:"}) for (int split = 0; split <= bad.length(); split++) {
            var output = new StringBuilder(); var guard = new AssistantCitationGuard(refs, output::append); int point = split;
            assertThatThrownBy(() -> { guard.accept("事实：" + bad.substring(0, point)); guard.accept(bad.substring(point)); guard.finish(); }).isInstanceOf(ApiException.class);
            assertThat(output.toString()).isEqualTo("事实：");
        }
    }

}
