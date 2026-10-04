package org.trigger.opspilot.oncall;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

/** One worker, no queued ticks. Network waits must not occupy the shared scheduling thread. */
@Component
public class OnCallSwapNotificationJob implements DisposableBean {
    private final OnCallSwapNotifications notifications;
    private final ExecutorService worker;
    public OnCallSwapNotificationJob(OnCallSwapNotifications notifications, OnCallSwapNotificationProperties properties) {
        this.notifications=notifications;
        worker=properties.enabled() || properties.retentionEnabled() ? new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),task->{
            var thread=new Thread(task,"oncall-swap-notification");thread.setDaemon(true);return thread;
        }) : null;
    }
    @Scheduled(fixedDelayString="${ONCALL_SWAP_NOTIFICATION_DISPATCH_DELAY:5000}",initialDelayString="${ONCALL_SWAP_NOTIFICATION_DISPATCH_INITIAL_DELAY:5000}")
    public void tick() {
        if(worker==null)return;
        try { worker.execute(()->{notifications.purgeExpiredPayloads();notifications.dispatchDue();}); }
        catch(RejectedExecutionException busy) { /* Existing bounded batch will finish; future tick queries SQL again. */ }
    }
    @Override public void destroy() throws InterruptedException {
        if(worker!=null){worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS);}
    }
}
