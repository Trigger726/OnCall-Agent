package org.trigger.opspilot.runbook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RunbookRetrievalLifecycleJob {
    private static final Logger log = LoggerFactory.getLogger(RunbookRetrievalLifecycleJob.class);

    private final RunbookRetrievalFeedbackService feedbackService;

    public RunbookRetrievalLifecycleJob(RunbookRetrievalFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @Scheduled(cron = "${opspilot.runbook.retrieval.lifecycle.cleanup-cron:0 15 3 * * *}")
    public void purgeExpiredSnapshots() {
        RunbookRetrievalFeedbackService.RetentionCleanupResult result = feedbackService.purgeExpired(null);
        if (result.purgedSnapshots() > 0) {
            log.info("Purged {} expired runbook retrieval snapshots", result.purgedSnapshots());
        }
    }
}
