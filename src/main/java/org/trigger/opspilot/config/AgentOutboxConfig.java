package org.trigger.opspilot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "opspilot.agent.events.outbox-enabled", havingValue = "true")
public class AgentOutboxConfig {
    @Bean
    public ThreadPoolTaskScheduler agentOutboxScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("opspilot-outbox-");
        return scheduler;
    }
}
