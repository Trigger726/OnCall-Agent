package org.trigger.opspilot.runbook;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunbookJudgmentAgreementTest {
    @Test
    void shouldCalculateLinearWeightedKappaForOrdinalGrades() {
        RunbookRetrievalFeedbackService.AgreementView agreement =
                RunbookRetrievalFeedbackService.calculateAgreement(List.of(
                        new RunbookRetrievalFeedbackService.GradePair(3, 3),
                        new RunbookRetrievalFeedbackService.GradePair(2, 2),
                        new RunbookRetrievalFeedbackService.GradePair(0, 1)));

        assertThat(agreement.sampleCount()).isEqualTo(3);
        assertThat(agreement.exactAgreementRate()).isEqualByComparingTo("0.666667");
        assertThat(agreement.withinOneAgreementRate()).isEqualByComparingTo("1.000000");
        assertThat(agreement.linearWeightedKappa()).isEqualByComparingTo("0.727273");
    }

    @Test
    void shouldReturnUndefinedKappaWithoutLabelVariation() {
        RunbookRetrievalFeedbackService.AgreementView agreement =
                RunbookRetrievalFeedbackService.calculateAgreement(List.of(
                        new RunbookRetrievalFeedbackService.GradePair(3, 3),
                        new RunbookRetrievalFeedbackService.GradePair(3, 3)));

        assertThat(agreement.exactAgreementRate()).isEqualByComparingTo("1.000000");
        assertThat(agreement.linearWeightedKappa()).isNull();
    }

    @Test
    void shouldReportEmptyAgreementDataset() {
        RunbookRetrievalFeedbackService.AgreementView agreement =
                RunbookRetrievalFeedbackService.calculateAgreement(List.of());

        assertThat(agreement.sampleCount()).isZero();
        assertThat(agreement.linearWeightedKappa()).isNull();
    }
}
