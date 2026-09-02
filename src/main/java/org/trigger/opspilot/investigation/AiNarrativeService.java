package org.trigger.opspilot.investigation;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "opspilot.ai", name = "enabled", havingValue = "true")
public class AiNarrativeService {
    private final ChatClient chatClient;

    public AiNarrativeService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public String narrate(String incidentTitle, String deterministicHypothesis, String evidenceJson) {
        return chatClient.prompt()
                .system("""
                        你是企业信息系统的 Incident 调查助手。你只能使用用户提供的证据，禁止补造日志、指标、时间或因果关系。
                        输出一段不超过 250 字的中文研判摘要，明确区分“已观察事实”“推测根因”“下一步验证”。
                        若证据不足，必须直接说明证据不足。
                        """)
                .user("Incident: " + incidentTitle + "\n规则引擎假设: " + deterministicHypothesis
                        + "\n结构化证据: " + evidenceJson)
                .call()
                .content();
    }
}
