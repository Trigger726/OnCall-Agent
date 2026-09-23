package org.trigger.opspilot.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class AlertmanagerRejectionLifecycleJob {
    private static final Logger log = LoggerFactory.getLogger(AlertmanagerRejectionLifecycleJob.class);
    private final AlertmanagerRejectionService rejectionService;
    private final AlertmanagerWebhookService webhookService;
    private final AlertmanagerRejectionProperties properties;

    public AlertmanagerRejectionLifecycleJob(AlertmanagerRejectionService rejectionService,
                                             AlertmanagerWebhookService webhookService,
                                             AlertmanagerRejectionProperties properties) {
        this.rejectionService = rejectionService;
        this.webhookService = webhookService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${opspilot.alertmanager.rejections.auto-replay-delay:5000}",
            initialDelayString = "${opspilot.alertmanager.rejections.auto-replay-initial-delay:30000}")
    public void replayDue() {
        if (!properties.isAutoReplayEnabled()) return;
        LocalDateTime now = LocalDateTime.now();
        for (long id : rejectionService.dueAutomaticReplayIds(now)) {
            try {
                webhookService.replayAutomatically(id, now);
            } catch (RuntimeException exception) {
                log.warn("Alertmanager rejection replay tick failed: rejectionId={}", id, exception);
            }
        }
    }

    @Scheduled(cron = "${opspilot.alertmanager.rejections.cleanup-cron:0 35 3 * * *}")
    public void purgeExpiredPayloads() {
        int purged = rejectionService.purgeExpired(LocalDateTime.now());
        if (purged > 0) log.info("Purged {} expired Alertmanager rejection payloads", purged);
    }
}
