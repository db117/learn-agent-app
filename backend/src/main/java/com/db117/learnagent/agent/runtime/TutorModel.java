package com.db117.learnagent.agent.runtime;

import com.db117.learnagent.config.ModelConfiguration;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.List;

/** Tutor 使用的可热切换模型；AgentScope Harness 始终持有本委托对象。 */
@ApplicationScoped
public class TutorModel implements Model {
    private volatile ActiveModel active = new ActiveModel(new ModelConfiguration("", "", ""), null);

    public TutorModel() {
    }

    TutorModel(Model model) {
        this.active = new ActiveModel(new ModelConfiguration("test-model", "", ""), model);
    }

    public boolean configured() {
        return active.model() != null;
    }

    public Model model() {
        return this;
    }

    public ModelConfiguration configuration() {
        return active.configuration();
    }

    public ModelConfiguration environmentConfiguration() {
        return new ModelConfiguration(
                environment("OPENAI_MODEL"),
                environment("OPENAI_BASE_URL"),
                environment("OPENAI_API_KEY"));
    }

    public Model createModel(ModelConfiguration configuration, boolean streaming) {
        if (configuration.modelName().isBlank()) {
            return null;
        }

        String baseUrl = configuration.baseUrl();
        boolean responsesApi = isResponsesEndpoint(baseUrl);
        HttpTransport transport = HttpTransportFactory.getDefault();
        if (responsesApi) {
            // ponytail: 只用 URL 路径后缀区分 Responses；需要更多协议时再加显式配置。
            transport = new OpenAIResponsesHttpTransport(transport);
        }
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(configuration.apiKey())
                .modelName(configuration.modelName())
                .stream(streaming)
                .httpTransport(transport);
        if (!baseUrl.isBlank()) {
            builder.baseUrl(responsesApi ? removeResponsesEndpoint(baseUrl) : baseUrl);
        }
        if (responsesApi) {
            builder.endpointPath("/responses");
        }
        return builder.build();
    }

    public synchronized void activate(ModelConfiguration configuration, Model model) {
        this.active = new ActiveModel(configuration, model);
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        Model model = active.model();
        if (model == null) {
            return Flux.error(new IllegalStateException("tutor model is not configured"));
        }
        return model.stream(messages, tools, options);
    }

    @Override
    public String getModelName() {
        Model model = active.model();
        return model == null ? "" : model.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        Model model = active.model();
        return model != null && model.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        Model model = active.model();
        return model != null && model.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        Model model = active.model();
        return model == null ? 0 : model.getContextWindowSize();
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

    /**
     * 原子切换模型与其配置，保证并发请求看到同一版本。
     *
     * @param configuration 当前生效的应用级配置
     * @param model 由该配置构建的模型；未配置或配置无效时为 null
     */
    private record ActiveModel(ModelConfiguration configuration, Model model) {
    }
}
