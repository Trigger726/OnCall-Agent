package org.trigger.opspilot.postmortem;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FollowUpEscalationJob {
    private final FollowUpEscalationService service;

    public FollowUpEscalationJob(FollowUpEscalationService service) {
        this.service = service;
    }

    @Scheduled(
            cron = "${opspilot.postmortem.follow-up.escalation-cron:0 5 9 * * *}",
            zone = "${opspilot.postmortem.follow-up.timezone:Asia/Shanghai}")
    public void scanOverdueFollowUps() {
        service.scan(null, null, "scheduler");
    }
}
