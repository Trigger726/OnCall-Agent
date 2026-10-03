package org.trigger.opspilot.assistant;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Locale;

@Service
@ConditionalOnProperty(prefix = "opspilot.ai", name = "enabled", havingValue = "true")
public class AssistantAiService {
    static final int MAX_STREAM_CHARACTERS = 100_000;
    static final int MAX_STREAM_RESPONSES = 10_000;
    private final ChatClient chatClient;

    public AssistantAiService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public String answer(String operationalContext, String recentConversation, String question) {
        return prompt(operationalContext, recentConversation, question).call().content();
    }

    /** Cold incremental provider stream. Only normal, nonempty completion is a complete answer. */
    public Flux<String> streamAnswer(String operationalContext, String recentConversation, String question) {
        return Flux.defer(() -> {
            var options = new DashScopeChatOptions();
            options.setIncrementalOutput(true);
            options.setEnableThinking(false);
            options.setInternalToolExecutionEnabled(false);
            var state = new StreamState();
            return prompt(operationalContext, recentConversation, question).options(options)
                    .stream().chatResponse()
                    .<String>handle((response, sink) -> {
                        String content = state.accept(response);
                        if (content != null && !content.isEmpty()) sink.next(content);
                    })
                    .concatWith(Flux.defer(() -> {
                        state.requireComplete();
                        return Flux.empty();
                    }));
        });
    }

    private ChatClient.ChatClientRequestSpec prompt(String operationalContext, String recentConversation, String question) {
        return chatClient.prompt()
                .system("""
                        你是 OpsPilot 企业信息系统 OnCall 助手。你的任务是协助值班工程师处置 Incident。
                        只能使用系统提供的 Incident、告警、CMDB、变更、调查报告和对话历史，不得虚构日志、指标或执行结果。
                        回答必须区分已知事实、研判和下一步动作；证据不足时明确说明。优先给出可验证、可回滚的操作。
                        使用简洁中文和 Markdown，避免泛泛而谈。不要执行状态变更，只能提出建议。
                        """)
                .user("系统上下文:\n" + operationalContext
                        + "\n\n最近对话:\n" + recentConversation
                        + "\n\n值班工程师问题:\n" + question);
    }

    private static final class StreamState {
        private int characters;
        private int responses;
        private boolean stopped;
        private boolean meaningful;

        String accept(ChatResponse response) {
            if (++responses > MAX_STREAM_RESPONSES || response == null || response.hasToolCalls()
                    || response.getResults().size() > 1) {
                throw new IllegalStateException("Invalid assistant provider stream");
            }
            // Providers may append a usage-only frame after their terminal generation.
            if (response.getResults().isEmpty()) return null;
            var generation = response.getResult();
            String text = generation.getOutput().getText();
            String reason = generation.getMetadata().getFinishReason();
            // DashScope maps wire "stop" to enum.name() ("STOP") in generation metadata.
            if (reason != null) reason = reason.toUpperCase(Locale.ROOT);
            boolean unfinished = reason == null || reason.isBlank() || "NULL".equals(reason);
            if (stopped && (!unfinished || (text != null && !text.isEmpty()))) {
                throw new IllegalStateException("Assistant provider emitted content after completion");
            }
            if (!unfinished && !"STOP".equals(reason)) {
                throw new IllegalStateException("Assistant provider did not complete normally");
            }
            if (text != null) {
                if (text.length() > MAX_STREAM_CHARACTERS - characters) {
                    throw new IllegalStateException("Assistant provider answer exceeds stream limit");
                }
                characters += text.length();
                meaningful |= !text.isBlank();
            }
            if ("STOP".equals(reason)) stopped = true;
            return text;
        }

        void requireComplete() {
            if (!stopped || !meaningful) {
                throw new IllegalStateException("Assistant provider stream ended without a complete answer");
            }
        }
    }
}
