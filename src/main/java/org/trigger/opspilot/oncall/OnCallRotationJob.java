package org.trigger.opspilot.oncall;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "opspilot.oncall.rotation", name = "enabled", matchIfMissing = true)
public class OnCallRotationJob {
    private final OnCallRotationService service;
    public OnCallRotationJob(OnCallRotationService service) { this.service = service; }

    @Scheduled(fixedDelayString = "${opspilot.oncall.rotation.scan-delay:60000}",
            initialDelayString = "${opspilot.oncall.rotation.initial-delay:60000}")
    public void extendRotations() { service.scan(null, null, "scheduler"); }
}
