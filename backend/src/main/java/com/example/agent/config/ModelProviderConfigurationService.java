package com.example.agent.config;

import com.example.agent.persistence.SqliteRepository;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Reads and validates the app-wide model configuration. */
@Component
public final class ModelProviderConfigurationService {

    static final String PROVIDER_KEY = "llm.provider";
    static final String BASE_URL_KEY = "llm.base-url";
    static final String API_KEY_KEY = "llm.api-key";
    static final String MODEL_KEY = "llm.model";

    private final SqliteRepository repository;
    private final ConfigurableEnvironment environment;
    private final String defaultBaseUrl;
    private final String defaultApiKey;
    private final String defaultModel;

    public ModelProviderConfigurationService(
            SqliteRepository repository,
            ConfigurableEnvironment environment,
            @Value("${app.openai.base-url:https://api.openai.com}") String defaultBaseUrl,
            @Value("${app.openai.api-key:}") String defaultApiKey,
            @Value("${app.openai.model:gpt-4.1-mini}") String defaultModel) {
        this.repository = repository;
        this.environment = environment;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultApiKey = defaultApiKey;
        this.defaultModel = defaultModel;
    }

    /** Returns the effective configuration; explicit environment variables win over saved settings. */
    public ModelProviderConfiguration current() {
        String provider = stored(PROVIDER_KEY).filter(value -> !value.isBlank())
                .orElse(ModelProviderConfiguration.OPENAI_COMPATIBLE);
        String baseUrl = environmentOrStored("OPENAI_BASE_URL", BASE_URL_KEY, defaultBaseUrl);
        String apiKey = environmentOrStored("OPENAI_API_KEY", API_KEY_KEY, defaultApiKey);
        String model = environmentOrStored("OPENAI_MODEL", MODEL_KEY, defaultModel);
        return normalize(new ModelProviderConfiguration(provider, baseUrl, apiKey, model), true);
    }

    /** Saves the app configuration while preserving a saved API key when the request omits it. */
    public ModelProviderConfiguration save(ModelProviderConfiguration requested) {
        if (requested == null) throw new IllegalArgumentException("model configuration is required");
        String apiKey = requested.apiKey() == null
                ? stored(API_KEY_KEY).orElse(defaultApiKey)
                : requested.apiKey();
        ModelProviderConfiguration normalized = normalize(
                new ModelProviderConfiguration(requested.provider(), requested.baseUrl(), apiKey, requested.model()), true);
        repository.upsertSetting(PROVIDER_KEY, normalized.provider());
        repository.upsertSetting(BASE_URL_KEY, normalized.baseUrl());
        repository.upsertSetting(MODEL_KEY, normalized.model());
        if (requested.apiKey() != null) repository.upsertSetting(API_KEY_KEY, normalized.apiKey());
        return current();
    }

    /** Verifies a candidate configuration with one short model request without persisting it. */
    public void test(ModelProviderConfiguration requested) {
        if (requested == null) throw new IllegalArgumentException("model configuration is required");
        ModelProviderConfiguration current = current();
        String apiKey = requested.apiKey() == null ? current.apiKey() : requested.apiKey();
        ModelProviderConfiguration candidate = normalize(new ModelProviderConfiguration(
                requested.provider() == null ? current.provider() : requested.provider(),
                requested.baseUrl() == null ? current.baseUrl() : requested.baseUrl(),
                apiKey,
                requested.model() == null ? current.model() : requested.model()), false);
        if (candidate.apiKey().isBlank()) throw new IllegalArgumentException("API Key 不能为空");
        try {
            ChatResponse response = createModel(candidate)
                    .stream(List.of(new UserMessage("Reply with OK.")), List.of(), GenerateOptions.builder().build())
                    .next()
                    .block(Duration.ofSeconds(30));
            if (response == null) throw new IllegalStateException("模型没有返回响应");
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ModelProviderConnectionException();
        }
    }

    /** Identifies whether the effective values come from environment, App settings, or defaults. */
    public String source() {
        if (environmentProperty("OPENAI_BASE_URL").isPresent()
                || environmentProperty("OPENAI_API_KEY").isPresent()
                || environmentProperty("OPENAI_MODEL").isPresent()) {
            return "environment";
        }
        if (stored(PROVIDER_KEY).isPresent() || stored(BASE_URL_KEY).isPresent()
                || stored(API_KEY_KEY).isPresent() || stored(MODEL_KEY).isPresent()) {
            return "app";
        }
        return "default";
    }

    /** Builds the single supported OpenAI-compatible AgentScope model. */
    Model createModel(ModelProviderConfiguration configuration) {
        return OpenAIChatModel.builder()
                .apiKey(configuration.apiKey())
                .baseUrl(configuration.baseUrl())
                .modelName(configuration.model())
                .stream(true)
                .build();
    }

    private ModelProviderConfiguration normalize(ModelProviderConfiguration configuration, boolean allowBlankApiKey) {
        String provider = required(configuration.provider(), "provider", 80);
        if (!ModelProviderConfiguration.OPENAI_COMPATIBLE.equals(provider)) {
            throw new IllegalArgumentException("当前仅支持 openai-compatible 模型接口");
        }
        String baseUrl = required(configuration.baseUrl(), "Base URL", 200);
        baseUrl = normalizeBaseUrl(baseUrl);
        String model = required(configuration.model(), "model", 200);
        String apiKey = configuration.apiKey() == null ? "" : configuration.apiKey().trim();
        if (apiKey.length() > 2_000) throw new IllegalArgumentException("API Key 过长");
        if (!allowBlankApiKey && apiKey.isBlank()) throw new IllegalArgumentException("API Key 不能为空");
        return new ModelProviderConfiguration(provider, baseUrl, apiKey, model);
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value.replaceFirst("/+$", "");
        try {
            URI uri = URI.create(normalized);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Base URL 必须是 http 或 https 地址，且不能包含凭据、查询参数或片段");
            }
        } catch (IllegalArgumentException error) {
            if (error.getMessage() != null && error.getMessage().startsWith("Base URL")) throw error;
            throw new IllegalArgumentException("Base URL 无效");
        }
        return normalized;
    }

    private String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) throw new IllegalArgumentException(field + " 过长");
        return trimmed;
    }

    private String environmentOrStored(String environmentKey, String settingKey, String fallback) {
        return environmentProperty(environmentKey)
                .map(String::trim)
                .orElseGet(() -> stored(settingKey).orElse(fallback));
    }

    private Optional<String> stored(String key) {
        return repository.findSetting(key).map(String::trim);
    }

    private Optional<String> environmentProperty(String key) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            Object value = source.getProperty(key);
            if (value != null) return Optional.of(value.toString());
        }
        return Optional.empty();
    }

    public static final class ModelProviderConnectionException extends RuntimeException {

        public ModelProviderConnectionException() {
            super("模型连接失败，请检查 Base URL、模型名称和 API Key");
        }
    }
}
