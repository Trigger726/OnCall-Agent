package org.trigger.opspilot.slo;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SloBurnRateCalculatorTest {
    @Test
    void shouldRequireBothFastPageWindowsToExceedThreshold() {
        Map<String, SloBurnRateCalculator.WindowSample> samples = healthy();
        samples.put("1h", sample(9800, 10000));
        samples.put("5m", sample(9995, 10000));

        SloBurnRateCalculator.Assessment assessment = SloBurnRateCalculator.assess(99.9, samples);

        assertThat(assessment.status()).isEqualTo("HEALTHY");
        assertThat(assessment.lanes().get(0).longBurnRate()).isEqualTo(20);
        assertThat(assessment.lanes().get(0).shortBurnRate()).isEqualTo(0.5);
        assertThat(assessment.lanes().get(0).status()).isEqualTo("OK");
    }

    @Test
    void shouldPrioritizeFastPageWhenBothWindowsBurnAboveFourteenPointFour() {
        Map<String, SloBurnRateCalculator.WindowSample> samples = healthy();
        samples.put("1h", sample(9850, 10000));
        samples.put("5m", sample(9850, 10000));

        SloBurnRateCalculator.Assessment assessment = SloBurnRateCalculator.assess(99.9, samples);

        assertThat(assessment.status()).isEqualTo("PAGE_FAST");
        assertThat(assessment.severity()).isEqualTo("PAGE");
        assertThat(assessment.lanes().get(0).status()).isEqualTo("FIRING");
        assertThat(assessment.lanes().get(0).budgetConsumedPercent()).isEqualTo(2);
    }

    @Test
    void shouldDistinguishSlowPageAndTicketPolicies() {
        Map<String, SloBurnRateCalculator.WindowSample> slow = healthy();
        slow.put("6h", sample(9930, 10000));
        slow.put("30m", sample(9930, 10000));
        assertThat(SloBurnRateCalculator.assess(99.9, slow).status()).isEqualTo("PAGE_SLOW");

        Map<String, SloBurnRateCalculator.WindowSample> ticket = healthy();
        ticket.put("3d", sample(9980, 10000));
        ticket.put("6h", sample(9980, 10000));
        assertThat(SloBurnRateCalculator.assess(99.9, ticket).status()).isEqualTo("TICKET");
    }

    @Test
    void shouldExposeMissingOrContradictoryWindowInsteadOfCalculating() {
        Map<String, SloBurnRateCalculator.WindowSample> missing = healthy();
        missing.put("3d", new SloBurnRateCalculator.WindowSample("NO_DATA", null, null, "3d 无分母"));
        assertThat(SloBurnRateCalculator.assess(99.9, missing).status()).isEqualTo("NO_DATA");

        Map<String, SloBurnRateCalculator.WindowSample> invalid = healthy();
        invalid.put("1h", new SloBurnRateCalculator.WindowSample(
                "INVALID_DATA", 101.0, 100.0, "1h 计数矛盾"));
        SloBurnRateCalculator.Assessment assessment = SloBurnRateCalculator.assess(99.9, invalid);
        assertThat(assessment.status()).isEqualTo("INVALID_DATA");
        assertThat(assessment.lanes().get(0).longBurnRate()).isNull();
    }

    private static Map<String, SloBurnRateCalculator.WindowSample> healthy() {
        Map<String, SloBurnRateCalculator.WindowSample> samples = new HashMap<>();
        SloBurnRateCalculator.requiredWindows().forEach(window -> samples.put(window, sample(10000, 10000)));
        return samples;
    }

    private static SloBurnRateCalculator.WindowSample sample(double good, double total) {
        return new SloBurnRateCalculator.WindowSample("VALID", good, total, null);
    }
}
