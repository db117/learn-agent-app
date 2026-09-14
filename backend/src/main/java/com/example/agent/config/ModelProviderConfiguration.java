package com.example.agent.config;

/** 应用级模型提供商连接配置。 */
public record ModelProviderConfiguration(String provider, String baseUrl, String apiKey, String model) {

    public static final String OPENAI_COMPATIBLE = "openai-compatible";
}
