package org.trigger.opspilot.investigation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(name = "opspilot.agent.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class AgentRunRecoveryJob {
    private static final Logger log = LoggerFactory.getLogger(AgentRunRecoveryJob.class);
    private final AgentRunRecoveryService service;

    public AgentRunRecoveryJob(AgentRunRecoveryService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${opspilot.agent.recovery.delay:5000}",
            initialDelayString = "${opspilot.agent.recovery.initial-delay:5000}")
    public void reconcileExpiredRuns() {
        AgentRunRecoveryService.RecoveryResult result = service.reconcileExpired(LocalDateTime.now());
        if (result.settled() > 0) {
            log.warn("Recovered {} Agent runs without terminal state: {}",
                    result.settled(), result.settledRunIds());
        }
    }
}
