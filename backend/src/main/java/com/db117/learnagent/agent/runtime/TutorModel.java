package com.db117.learnagent.agent.runtime;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;

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
        String apiKey = environment("OPENAI_API_KEY");
        String modelName = environment("OPENAI_MODEL");
        if (apiKey.isBlank() || modelName.isBlank()) {
            return null;
        }

        try {
            String baseUrl = environment("OPENAI_BASE_URL");
            boolean responsesApi = isResponsesEndpoint(baseUrl);
            HttpTransport transport = HttpTransportFactory.getDefault();
            if (responsesApi) {
                // ponytail: 只用 URL 路径后缀区分 Responses；需要更多协议时再加显式配置。
                transport = new OpenAIResponsesHttpTransport(transport);
            }
            OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .stream(true)
                    .httpTransport(transport);
            if (!baseUrl.isBlank()) {
                builder.baseUrl(responsesApi ? removeResponsesEndpoint(baseUrl) : baseUrl);
            }
            if (responsesApi) {
                builder.endpointPath("/responses");
            }
            return builder.build();
        } catch (RuntimeException ignored) {
            // 配置错误不应阻止健康检查；发送阶段只返回 MODEL_UNAVAILABLE。
            return null;
        }
    }

    static boolean isResponsesEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            String path = URI.create(baseUrl).getRawPath();
            return path != null && trimTrailingSlashes(path).endsWith("/responses");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static String removeResponsesEndpoint(String baseUrl) {
        if (!isResponsesEndpoint(baseUrl)) {
            return baseUrl;
        }
        URI uri = URI.create(baseUrl);
        String path = uri.getRawPath();
        String normalizedPath = trimTrailingSlashes(path);
        int endpointIndex = normalizedPath.length() - "/responses".length();
        int pathStart = baseUrl.indexOf(path);
        if (pathStart < 0) {
            return baseUrl;
        }
        int pathEnd = pathStart + path.length();
        return baseUrl.substring(0, pathStart + endpointIndex)
                + baseUrl.substring(pathEnd);
    }

    private static String trimTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private String environment(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }
}
