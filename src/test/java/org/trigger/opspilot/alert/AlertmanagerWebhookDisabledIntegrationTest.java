package org.trigger.opspilot.alert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:opspilot-alertmanager-disabled;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "opspilot.alertmanager.webhook.secret=disabled",
        "opspilot.agent.recovery.enabled=false"
})
@AutoConfigureMockMvc
class AlertmanagerWebhookDisabledIntegrationTest {
    @Autowired private MockMvc mockMvc;

    @Test
    void shouldRefuseWebhookWhenSecretIsNotConfigured() throws Exception {
        mockMvc.perform(post("/api/v1/integrations/alertmanager/webhook")
                        .header("Authorization", "OpsPilot any-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alerts\":[{}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("ALERTMANAGER_WEBHOOK_DISABLED"));
    }
}
