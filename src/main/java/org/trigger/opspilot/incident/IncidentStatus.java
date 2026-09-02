package org.trigger.opspilot.incident;

import java.util.EnumSet;
import java.util.Map;

public enum IncidentStatus {
    OPEN,
    ACKNOWLEDGED,
    INVESTIGATING,
    MITIGATED,
    RESOLVED,
    CLOSED;

    private static final Map<IncidentStatus, EnumSet<IncidentStatus>> TRANSITIONS = Map.of(
            OPEN, EnumSet.of(ACKNOWLEDGED),
            ACKNOWLEDGED, EnumSet.of(INVESTIGATING),
            INVESTIGATING, EnumSet.of(MITIGATED),
            MITIGATED, EnumSet.of(INVESTIGATING, RESOLVED),
            RESOLVED, EnumSet.of(INVESTIGATING, CLOSED),
            CLOSED, EnumSet.noneOf(IncidentStatus.class)
    );

    public boolean canTransitionTo(IncidentStatus target) {
        return TRANSITIONS.get(this).contains(target);
    }
}
