package com.example.agent.config;

/** App-wide model provider connection settings. */
public record ModelProviderConfiguration(String provider, String baseUrl, String apiKey, String model) {

    public static final String OPENAI_COMPATIBLE = "openai-compatible";
}
