package org.trigger.opspilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.trigger.opspilot.postmortem.FollowUpNotificationProperties;
import org.trigger.opspilot.oncall.OnCallSwapNotificationProperties;
import org.trigger.opspilot.oncall.OnCallOpenNotificationProperties;

@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties({FollowUpNotificationProperties.class, OnCallSwapNotificationProperties.class,OnCallOpenNotificationProperties.class})
public class OpsPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsPilotApplication.class, args);
    }
}
