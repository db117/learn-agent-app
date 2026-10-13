package com.db117.learnagent.agent.runtime;

import com.db117.learnagent.config.ModelConfiguration;
import com.db117.learnagent.config.OpenAIProtocol;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelHttpException;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.exception.OpenAIException;
import jakarta.enterprise.context.ApplicationScoped;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tutor 使用的可热切换模型；AgentScope Harness 始终持有本委托对象。 */
@ApplicationScoped
public class TutorModel implements Model {
    private static final int MAX_MODEL_RETRIES = 5;
    private static final Duration MODEL_RETRY_INITIAL_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MODEL_RETRY_MAX_BACKOFF = Duration.ofSeconds(16);

    private volatile ActiveModel active = new ActiveModel(
            new ModelConfiguration("", "", "", OpenAIProtocol.CHAT_COMPLETIONS), null);

    public TutorModel() {
    }

    TutorModel(Model model) {
        this.active = new ActiveModel(new ModelConfiguration(
                "test-model", "", "", OpenAIProtocol.CHAT_COMPLETIONS), model);
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
                environment("OPENAI_API_KEY"),
                environmentProtocol());
    }

    public Model createModel(ModelConfiguration configuration, boolean streaming) {
        if (configuration.protocol() == OpenAIProtocol.RESPONSES) {
            throw new IllegalArgumentException("Responses API 暂不可用，请选择 Chat Completions");
        }
        if (configuration.modelName().isBlank()) {
            return null;
        }

        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(configuration.apiKey())
                .modelName(configuration.modelName())
                .stream(streaming);
        if (!configuration.baseUrl().isBlank()) {
            builder.baseUrl(configuration.baseUrl());
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
        return Flux.defer(() -> {
            AtomicBoolean responseEmitted = new AtomicBoolean();
            return Flux.defer(() -> model.stream(messages, tools, options)
                            .doOnNext(ignored -> responseEmitted.set(true)))
                    .retryWhen(Retry.backoff(MAX_MODEL_RETRIES, MODEL_RETRY_INITIAL_BACKOFF)
                            .maxBackoff(MODEL_RETRY_MAX_BACKOFF)
                            .jitter(0)
                            .filter(error -> !responseEmitted.get() && isRetryableModelFailure(error))
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
        });
    }

    private static boolean isRetryableModelFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ModelHttpException modelHttpException
                    && (modelHttpException.isRetryableHttpStatus()
                    || Integer.valueOf(408).equals(modelHttpException.getStatusCode()))) {
                return true;
            }
            if (cause instanceof HttpTransportException transportException
                    && transportException.isRetryable()) {
                return true;
            }
            // 兼容网关会把流内的这类临时上游故障错误地标成 HTTP 400。
            if (cause instanceof OpenAIException
                    && cause.getMessage() != null
                    && cause.getMessage().toLowerCase(Locale.ROOT)
                    .contains("upstream service temporarily unavailable")) {
                return true;
            }
        }
        return false;
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

    private String environment(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    private OpenAIProtocol environmentProtocol() {
        // 环境变量未指定时使用通用兼容服务的默认协议。
        return "RESPONSES".equalsIgnoreCase(environment("OPENAI_PROTOCOL"))
                ? OpenAIProtocol.RESPONSES
                : OpenAIProtocol.CHAT_COMPLETIONS;
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
