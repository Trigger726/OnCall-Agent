package org.trigger.opspilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AgentExecutionConfig {
    @Bean(name = "agentTaskExecutor")
    public ThreadPoolTaskExecutor agentTaskExecutor(
            @Value("${opspilot.agent.executor.core-pool-size:2}") int corePoolSize,
            @Value("${opspilot.agent.executor.max-pool-size:4}") int maxPoolSize,
            @Value("${opspilot.agent.executor.queue-capacity:50}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("opspilot-agent-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
