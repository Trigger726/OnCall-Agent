package org.trigger.opspilot.assistant;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Only whole, identity-only questions bypass incident retrieval. Never infer a model from its prose. */
@Component
public class AssistantIdentity {
    private static final Pattern QUESTIONS = Pattern.compile("(?:(?:请问|你好)?(?:你是谁|你叫什么|你叫什么名字|介绍一下你自己|介绍一下自己|你是什么助手|你能做什么|你有什么功能|你是什么模型|你是什么大模型|你用的是什么模型|你用什么模型|你使用什么模型|你使用的是什么模型|你是哪个模型|你是什么版本|你是deepseek吗|你是claude吗|你是chatgpt吗|whoareyou|whatmodelareyou|whichmodelareyou|whatcanyoudo))+|你好|hello|hi");
    private final RuntimeView runtime;

    public AssistantIdentity(Optional<AssistantAiService> ai, Optional<ChatModel> model) {
        String configured = model.map(ChatModel::getDefaultOptions).map(options -> options.getModel()).orElse(null);
        // Expose a model identifier, never arbitrary configuration/URLs or credentials.
        if (configured != null && !configured.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) configured = null;
        runtime = ai.isPresent()
                ? new RuntimeView("MODEL", "通义 DashScope", configured, "模型已配置")
                : new RuntimeView("RULES", null, null, "规则与证据模式");
    }

    public RuntimeView runtime() { return runtime; }

    public Optional<String> answer(String question) {
        if (question == null || question.length() > 220) return Optional.empty();
        String normalized = question.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
        if (!QUESTIONS.matcher(normalized).matches()) return Optional.empty();
        String mode = "RULES".equals(runtime.mode())
                ? "当前是**规则与证据模式**，未启用大语言模型；本次回答不来自 Claude、DeepSeek 或 ChatGPT。"
                : "当前接入**通义 DashScope**，" + (runtime.model() == null
                    ? "具体模型名称未能从部署配置确认，我不会猜测型号。"
                    : "配置模型为 **" + runtime.model() + "**。")
                    + "这是部署配置，不代表模型服务此刻可用；本次身份说明由系统直接回答，没有调用模型。";
        return Optional.of("我是 **OpsPilot OnCall 助手**，帮助值班工程师查看告警、分析 Incident、检索处置手册和梳理验证步骤。\n\n"
                + mode + "\n\n我不会自动执行生产操作；故障判断需要结合可验证的证据。");
    }

    public record RuntimeView(String mode, String provider, String model, String label) { }
}
