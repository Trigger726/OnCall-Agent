package org.trigger.opspilot.incident;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentStatusTest {
    @Test
    void shouldAllowOnlyExplicitStateTransitions() {
        assertThat(IncidentStatus.OPEN.canTransitionTo(IncidentStatus.ACKNOWLEDGED)).isTrue();
        assertThat(IncidentStatus.OPEN.canTransitionTo(IncidentStatus.RESOLVED)).isFalse();
        assertThat(IncidentStatus.INVESTIGATING.canTransitionTo(IncidentStatus.MITIGATED)).isTrue();
        assertThat(IncidentStatus.RESOLVED.canTransitionTo(IncidentStatus.INVESTIGATING)).isTrue();
        assertThat(IncidentStatus.CLOSED.canTransitionTo(IncidentStatus.OPEN)).isFalse();
    }
}
