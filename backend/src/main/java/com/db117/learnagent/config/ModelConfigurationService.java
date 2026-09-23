package com.db117.learnagent.config;

import com.db117.learnagent.agent.runtime.TutorModel;
import com.db117.learnagent.persistence.sqlite.SqliteModelConfigurationRepository;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/** 应用层负责保存、校验并立即激活全局 Tutor 模型配置。 */
@ApplicationScoped
public class ModelConfigurationService {
    private static final int MAX_MODEL_NAME_LENGTH = 200;
    private static final int MAX_BASE_URL_LENGTH = 2_048;
    private static final int MAX_API_KEY_LENGTH = 4_096;
    private static final Duration CONNECTION_TEST_TIMEOUT = Duration.ofSeconds(30);

    private final SqliteModelConfigurationRepository repository;
    private final TutorModel tutorModel;

    public ModelConfigurationService(
            SqliteModelConfigurationRepository repository,
            TutorModel tutorModel) {
        this.repository = repository;
        this.tutorModel = tutorModel;
    }

    public synchronized void initialize() {
        ModelConfiguration configuration = repository.find()
                .orElseGet(tutorModel::environmentConfiguration);
        Model model = null;
        try {
            model = tutorModel.createModel(configuration, true);
        } catch (RuntimeException ignored) {
            // 无效环境变量或损坏的本地配置不应阻止后端启动。
        }
        tutorModel.activate(configuration, model);
    }

    public synchronized ConfigurationView current() {
        return view(currentConfiguration());
    }

    public synchronized ConfigurationView save(
            String modelName,
            OpenAIProtocol protocol,
            String baseUrl,
            String apiKey,
            boolean clearApiKey) {
        ModelConfiguration configuration = resolveConfiguration(
                modelName, protocol, baseUrl, apiKey, clearApiKey);

        Model model;
        try {
            model = tutorModel.createModel(configuration, true);
        } catch (RuntimeException ignored) {
            throw new IllegalArgumentException("无法创建模型配置，请检查模型名称和 API 地址");
        }
        if (model == null) {
            throw new IllegalArgumentException("模型名称不能为空");
        }

        repository.save(configuration);
        tutorModel.activate(configuration, model);
        return view(configuration);
    }

    public synchronized void test(
            String modelName,
            OpenAIProtocol protocol,
            String baseUrl,
            String apiKey,
            boolean clearApiKey) {
        ModelConfiguration configuration = resolveConfiguration(
                modelName, protocol, baseUrl, apiKey, clearApiKey);
        Model model;
        try {
            model = tutorModel.createModel(configuration, false);
        } catch (RuntimeException ignored) {
            throw new IllegalArgumentException("无法创建模型配置，请检查模型名称和 API 地址");
        }
        if (model == null) {
            throw new IllegalArgumentException("模型名称不能为空");
        }

        try {
            if (model.stream(
                            List.of(new UserMessage("Reply with OK.")),
                            List.of(),
                            GenerateOptions.builder().maxTokens(8).build())
                    .blockLast(CONNECTION_TEST_TIMEOUT) == null) {
                throw new ModelConnectionException();
            }
        } catch (RuntimeException ignored) {
            // Provider 异常可能包含响应正文；API 只返回稳定且不含凭据的错误。
            throw new ModelConnectionException();
        }
    }

    private ModelConfiguration currentConfiguration() {
        return repository.find().orElseGet(tutorModel::configuration);
    }

    private ModelConfiguration resolveConfiguration(
            String modelName,
            OpenAIProtocol protocol,
            String baseUrl,
            String apiKey,
            boolean clearApiKey) {
        String normalizedModelName = normalizeModelName(modelName);
        if (protocol == null) {
            throw new IllegalArgumentException("请选择 OpenAI 协议");
        }
        if (protocol == OpenAIProtocol.RESPONSES) {
            throw new IllegalArgumentException("Responses API 暂不可用，请选择 Chat Completions");
        }
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        String submittedApiKey = apiKey == null ? "" : apiKey.trim();
        if (submittedApiKey.length() > MAX_API_KEY_LENGTH) {
            throw new IllegalArgumentException("API Key 过长");
        }
        ModelConfiguration previous = currentConfiguration();
        return new ModelConfiguration(
                normalizedModelName,
                normalizedBaseUrl,
                resolveApiKey(previous, normalizedBaseUrl, submittedApiKey, clearApiKey),
                protocol);
    }

    private ConfigurationView view(ModelConfiguration configuration) {
        String modelName = configuration.modelName().isBlank() ? null : configuration.modelName();
        return new ConfigurationView(
                tutorModel.configured(),
                modelName,
                configuration.protocol(),
                configuration.baseUrl(),
                !configuration.apiKey().isBlank());
    }

    private static String normalizeModelName(String modelName) {
        String normalized = modelName == null ? "" : modelName.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        if (normalized.length() > MAX_MODEL_NAME_LENGTH) {
            throw new IllegalArgumentException("模型名称过长");
        }
        return normalized;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.length() > MAX_BASE_URL_LENGTH) {
            throw new IllegalArgumentException("API 地址过长");
        }
        if (normalized.isEmpty()) {
            return normalized;
        }

        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("API 地址格式无效");
        }
        String scheme = uri.getScheme();
        if (!uri.isAbsolute()
                || uri.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("API 地址必须是有效的 HTTP 或 HTTPS 地址");
        }
        return normalized;
    }

    private static String resolveApiKey(
            ModelConfiguration previous,
            String baseUrl,
            String submittedApiKey,
            boolean clearApiKey) {
        if (!submittedApiKey.isBlank()) {
            return submittedApiKey;
        }
        if (clearApiKey || !previous.baseUrl().equals(baseUrl)) {
            return "";
        }
        return previous.apiKey();
    }

    /** 不携带上游响应正文，避免错误文本包含敏感凭据。 */
    public static final class ModelConnectionException extends RuntimeException {
        public ModelConnectionException() {
            super("Unable to connect to model provider");
        }
    }

    /**
     * 返回 UI 可安全展示的配置摘要。
     *
     * @param configured 当前 Tutor 模型是否可用
     * @param modelName 配置的模型名称；未配置时为 null
     * @param protocol 发送请求时使用的 OpenAI 兼容协议
     * @param baseUrl 自定义 API 基础地址；空值表示 SDK 默认地址
     * @param apiKeyConfigured 是否已设置 API Key，不包含密钥本身
     */
    public record ConfigurationView(
            boolean configured,
            String modelName,
            OpenAIProtocol protocol,
            String baseUrl,
            boolean apiKeyConfigured) {
    }
}
