package org.trigger.opspilot.oncall;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.trigger.opspilot.common.ApiException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class OnCallOpenNotificationOperationsTest {
    @Test void shouldReportDisabledWorkerWithoutStartingOrDispatching()throws Exception{var n=mock(OnCallOpenNotifications.class);var p=mock(OnCallOpenNotificationProperties.class);var job=new OnCallOpenNotificationJob(n,p);job.tick();assertThat(job.snapshot().enabled()).isFalse();assertThat(job.snapshot().activeWorkers()).isZero();assertThat(job.snapshot().queuedTicks()).isZero();verifyNoInteractions(n);job.destroy();}
    @Test void shouldNotCountShutdownAsCapacitySaturation()throws Exception{var n=mock(OnCallOpenNotifications.class);var p=mock(OnCallOpenNotificationProperties.class);when(p.enabled()).thenReturn(true);var job=new OnCallOpenNotificationJob(n,p);job.destroy();job.tick();assertThat(job.snapshot().shutdown()).isTrue();assertThat(job.snapshot().busyRejectedTicks()).isZero();verifyNoInteractions(n);}
    @Test void shouldFailClosedOnDatabaseFailureWithoutFakeZeroQueue(){var jdbc=mock(JdbcClient.class);when(jdbc.sql(anyString())).thenThrow(new DataAccessResourceFailureException("private database detail"));var operations=new OnCallOpenNotificationOperations(jdbc,null,null);assertThatThrownBy(()->operations.get(1)).isInstanceOf(ApiException.class).hasMessageNotContaining("private database").extracting("code").isEqualTo("ONCALL_OPEN_NOTIFICATION_OPERATIONS_UNAVAILABLE");}
    @Test void shouldCountFailedBatchWithoutExposingExceptionDetails()throws Exception{var n=mock(OnCallOpenNotifications.class);var p=mock(OnCallOpenNotificationProperties.class);when(p.enabled()).thenReturn(true);when(n.dispatchDue()).thenAnswer(call->{Thread.currentThread().setUncaughtExceptionHandler((thread,error)->{});throw new IllegalStateException("controlled private worker detail");});var job=new OnCallOpenNotificationJob(n,p);try{job.tick();long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(job.snapshot().failedBatches()==0&&System.nanoTime()<until)Thread.sleep(10);assertThat(job.snapshot().failedBatches()).isEqualTo(1);assertThat(job.snapshot().toString()).doesNotContain("private worker detail");}finally{job.destroy();}}
}
