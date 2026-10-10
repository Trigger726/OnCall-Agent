package org.trigger.opspilot.oncall;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** One worker, zero queued ticks; durable SQL remains the only queue. */
@Component
public class OnCallOpenNotificationJob implements DisposableBean {
    private final OnCallOpenNotifications notifications;
    private final ThreadPoolExecutor worker;
    private final AtomicLong busyRejectedTicks=new AtomicLong();
    private final AtomicLong failedBatches=new AtomicLong();
    public OnCallOpenNotificationJob(OnCallOpenNotifications notifications,OnCallOpenNotificationProperties properties) {
        this.notifications=notifications;
        worker=properties.enabled()?new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),task->{
            var thread=new Thread(task,"oncall-open-notification");thread.setDaemon(true);return thread;
        }):null;
    }
    @Scheduled(fixedDelayString="${ONCALL_OPEN_NOTIFICATION_DISPATCH_DELAY:5000}",initialDelayString="${ONCALL_OPEN_NOTIFICATION_DISPATCH_INITIAL_DELAY:5000}")
    public void tick(){if(worker==null||worker.isShutdown())return;try{worker.execute(()->{
        try{notifications.dispatchDue();}catch(RuntimeException error){failedBatches.incrementAndGet();throw error;}
    });}catch(RejectedExecutionException busy){if(!worker.isShutdown())busyRejectedTicks.incrementAndGet();/* Next tick queries durable SQL. */}}
    public WorkerView snapshot(){return new WorkerView("THIS_PROCESS_SINCE_START",worker!=null,worker==null?0:worker.getActiveCount(),
            worker==null?0:worker.getQueue().size(),busyRejectedTicks.get(),failedBatches.get(),worker!=null&&worker.isShutdown());}
    public record WorkerView(String scope,boolean enabled,int activeWorkers,int queuedTicks,long busyRejectedTicks,long failedBatches,boolean shutdown) {}
    @Override public void destroy() throws InterruptedException {if(worker!=null){worker.shutdownNow();worker.awaitTermination(5,TimeUnit.SECONDS);}}
}
