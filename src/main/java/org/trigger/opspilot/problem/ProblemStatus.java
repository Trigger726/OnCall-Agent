package org.trigger.opspilot.problem;

public enum ProblemStatus {
    OPEN,
    KNOWN_ERROR,
    RESOLVED;

    public boolean canTransitionTo(ProblemStatus target) {
        if (this == target) return true;
        return switch (this) {
            case OPEN -> target == KNOWN_ERROR || target == RESOLVED;
            case KNOWN_ERROR -> target == OPEN || target == RESOLVED;
            case RESOLVED -> target == OPEN;
        };
    }
}
