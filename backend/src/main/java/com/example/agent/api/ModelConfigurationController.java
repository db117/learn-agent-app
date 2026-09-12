package com.example.agent.api;

import com.example.agent.config.ModelProviderConfiguration;
import com.example.agent.config.ModelProviderConfigurationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** App 内模型提供商配置接口；API Key 只以掩码形式返回。 */
@RestController
@RequestMapping("/api/settings/model")
public final class ModelConfigurationController {

    private final ModelProviderConfigurationService configuration;

    public ModelConfigurationController(ModelProviderConfigurationService configuration) {
        this.configuration = configuration;
    }

    @GetMapping
    public ModelConfigurationResponse get() {
        return response(configuration.current(), false);
    }

    @PutMapping
    public ModelConfigurationResponse save(@RequestBody(required = false) ModelConfigurationRequest request) {
        if (request == null) throw new IllegalArgumentException("model configuration is required");
        return response(configuration.save(request.toConfiguration()), true);
    }

    @PostMapping("/test")
    public ModelTestResponse test(@RequestBody(required = false) ModelConfigurationRequest request) {
        if (request == null) throw new IllegalArgumentException("model configuration is required");
        configuration.test(request.toConfiguration());
        return new ModelTestResponse("ok", "模型连接成功", request.model());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(new ErrorResponse("model_configuration_invalid", error.getMessage()));
    }

    @ExceptionHandler(ModelProviderConfigurationService.ModelProviderConnectionException.class)
    public ResponseEntity<ErrorResponse> connection(
            ModelProviderConfigurationService.ModelProviderConnectionException error) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("model_connection_failed", error.getMessage()));
    }

    private ModelConfigurationResponse response(ModelProviderConfiguration value, boolean restartRequired) {
        return new ModelConfigurationResponse(
                value.provider(), value.baseUrl(), value.model(), !value.apiKey().isBlank(), mask(value.apiKey()),
                configuration.source(), restartRequired);
    }

    private String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return "";
        return apiKey.length() <= 4 ? "••••" : "••••" + apiKey.substring(apiKey.length() - 4);
    }

    public record ModelConfigurationRequest(String provider, String baseUrl, String model, String apiKey) {

        ModelProviderConfiguration toConfiguration() {
            return new ModelProviderConfiguration(provider, baseUrl, apiKey, model);
        }
    }

    public record ModelConfigurationResponse(
            String provider,
            String baseUrl,
            String model,
            boolean apiKeyConfigured,
            String apiKeyMasked,
            String source,
            boolean restartRequired) {
    }

    public record ModelTestResponse(String status, String message, String model) {
    }

    public record ErrorResponse(String error, String detail) {
    }
}
