package com.db117.learnagent.agent.runtime;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import jakarta.enterprise.context.ApplicationScoped;

/** 从进程环境读取唯一模型配置；未配置时保持后端可启动，首次发送再返回安全错误。 */
@ApplicationScoped
public class TutorModel {
    private final Model model;

    public TutorModel() {
        this.model = buildFromEnvironment();
    }

    TutorModel(Model model) {
        this.model = model;
    }

    public boolean configured() {
        return model != null;
    }

    public Model model() {
        return model;
    }

    private Model buildFromEnvironment() {
        var apiKey = environment("OPENAI_API_KEY");
        var modelName = environment("OPENAI_MODEL");
        if (apiKey.isBlank() || modelName.isBlank()) {
            return null;
        }

        try {
            var builder = OpenAIChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .stream(true)
                    .httpTransport(new ProviderEnvelopeHttpTransport(HttpTransportFactory.getDefault()));
            var baseUrl = environment("OPENAI_BASE_URL");
            if (!baseUrl.isBlank()) {
                builder.baseUrl(baseUrl);
            }
            return builder.build();
        } catch (RuntimeException ignored) {
            // 配置错误不应阻止健康检查；发送阶段只返回 MODEL_UNAVAILABLE。
            return null;
        }
    }

    private String environment(String name) {
        var value = System.getenv(name);
        return value == null ? "" : value.trim();
    }
}
