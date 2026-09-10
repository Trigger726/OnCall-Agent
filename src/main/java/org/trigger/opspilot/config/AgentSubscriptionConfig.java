package org.trigger.opspilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class AgentSubscriptionConfig {
    @Bean
    public ThreadPoolTaskExecutor agentSubscriptionExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(128);
        executor.setThreadNamePrefix("opspilot-sse-");
        return executor;
    }

    @Bean
    public ThreadPoolTaskScheduler agentSubscriptionScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("opspilot-catchup-");
        return scheduler;
    }
}
