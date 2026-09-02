package org.trigger.opspilot.assistant;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "opspilot.ai", name = "enabled", havingValue = "true")
public class AssistantAiService {
    private final ChatClient chatClient;

    public AssistantAiService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public String answer(String operationalContext, String recentConversation, String question) {
        return chatClient.prompt()
                .system("""
                        你是 OpsPilot 企业信息系统 OnCall 助手。你的任务是协助值班工程师处置 Incident。
                        只能使用系统提供的 Incident、告警、CMDB、变更、调查报告和对话历史，不得虚构日志、指标或执行结果。
                        回答必须区分已知事实、研判和下一步动作；证据不足时明确说明。优先给出可验证、可回滚的操作。
                        使用简洁中文和 Markdown，避免泛泛而谈。不要执行状态变更，只能提出建议。
                        """)
                .user("系统上下文:\n" + operationalContext
                        + "\n\n最近对话:\n" + recentConversation
                        + "\n\n值班工程师问题:\n" + question)
                .call()
                .content();
    }
}
