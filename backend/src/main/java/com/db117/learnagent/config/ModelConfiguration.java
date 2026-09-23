package com.db117.learnagent.config;

/**
 * 应用级 Tutor 模型配置；API Key 仅供后端运行时使用，不通过配置查询接口返回。
 *
 * @param modelName OpenAI 兼容服务的模型名称
 * @param baseUrl OpenAI 兼容服务基础地址；空值表示使用 SDK 默认地址
 * @param apiKey API Key；空值表示服务不需要认证
 * @param protocol 发送请求时使用的 OpenAI 兼容协议
 */
public record ModelConfiguration(
        String modelName,
        String baseUrl,
        String apiKey,
        OpenAIProtocol protocol) {
    public ModelConfiguration {
        modelName = modelName == null ? "" : modelName.trim();
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        protocol = protocol == null ? OpenAIProtocol.CHAT_COMPLETIONS : protocol;
    }

    @Override
    public String toString() {
        return "ModelConfiguration[modelName=" + modelName
                + ", protocol=" + protocol
                + ", apiKeyConfigured=" + !apiKey.isEmpty() + "]";
    }
}
