package org.trigger.opspilot.oncall;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "opspilot.oncall.escalation", name = "enabled", matchIfMissing = true)
public class IncidentEscalationJob {
    private final IncidentEscalationService service;

    public IncidentEscalationJob(IncidentEscalationService service) {
        this.service = service;
    }

    @Scheduled(cron = "${opspilot.oncall.escalation.cron:0 * * * * *}",
            zone = "${opspilot.oncall.escalation.timezone:Asia/Shanghai}")
    public void scanOpenIncidents() {
        service.scan(null, null, "scheduler");
    }
}
