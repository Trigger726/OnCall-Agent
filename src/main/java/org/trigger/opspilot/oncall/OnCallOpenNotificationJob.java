package org.trigger.opspilot.oncall;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

/** One worker, zero queued ticks; durable SQL remains the only queue. */
@Component
public class OnCallOpenNotificationJob implements DisposableBean {
    private final OnCallOpenNotifications notifications;
    private final ExecutorService worker;
    public OnCallOpenNotificationJob(OnCallOpenNotifications notifications,OnCallOpenNotificationProperties properties) {
        this.notifications=notifications;
        worker=properties.enabled()?new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),task->{
            var thread=new Thread(task,"oncall-open-notification");thread.setDaemon(true);return thread;
        }):null;
    }
    @Scheduled(fixedDelayString="${ONCALL_OPEN_NOTIFICATION_DISPATCH_DELAY:5000}",initialDelayString="${ONCALL_OPEN_NOTIFICATION_DISPATCH_INITIAL_DELAY:5000}")
    public void tick(){if(worker==null)return;try{worker.execute(notifications::dispatchDue);}catch(RejectedExecutionException busy){/* Next tick queries durable SQL. */}}
    @Override public void destroy() throws InterruptedException {if(worker!=null){worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS);}}
}
