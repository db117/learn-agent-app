package com.db117.learnagent.api;

import com.db117.learnagent.config.ModelConfigurationService;
import com.db117.learnagent.config.ModelConfigurationService.ConfigurationView;
import com.db117.learnagent.config.ModelConfigurationService.ModelConnectionException;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** 全局 Tutor 模型设置 API；所有响应只提供密钥是否存在，不提供密钥内容。 */
@Path("/api/model-config")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ModelConfigurationResource {
    private final ModelConfigurationService service;

    public ModelConfigurationResource(ModelConfigurationService service) {
        this.service = service;
    }

    @GET
    public ConfigurationView get() {
        return service.current();
    }

    @PUT
    public Response save(ConfigurationRequest request) {
        if (request == null) {
            return invalid("模型配置不能为空");
        }
        try {
            return Response.ok(service.save(
                    request.modelName(), request.baseUrl(), request.apiKey(), request.clearApiKey())).build();
        } catch (IllegalArgumentException error) {
            return invalid(error.getMessage());
        }
    }

    @POST
    @Path("/test")
    public Response test(ConfigurationRequest request) {
        if (request == null) {
            return invalid("模型配置不能为空");
        }
        try {
            service.test(request.modelName(), request.baseUrl(), request.apiKey(), request.clearApiKey());
            return Response.ok(new ConnectionTestResponse(true, "连接成功")).build();
        } catch (IllegalArgumentException error) {
            return invalid(error.getMessage());
        } catch (ModelConnectionException error) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity(new ConfigurationError("MODEL_CONNECTION_FAILED", "连接失败，请检查 API 地址、模型名称和 API Key"))
                    .build();
        }
    }

    private static Response invalid(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ConfigurationError("INVALID_MODEL_CONFIGURATION", message))
                .build();
    }

    /**
     * 配置提交请求。
     *
     * @param modelName OpenAI 兼容服务中的模型名称
     * @param baseUrl 自定义 API 地址；空值表示使用 SDK 默认地址
     * @param apiKey 新 API Key；空值表示保留当前 Key，除非勾选清除或地址已更改
     * @param clearApiKey 是否明确清除当前 API Key
     */
    @RegisterForReflection
    public record ConfigurationRequest(
            String modelName,
            String baseUrl,
            String apiKey,
            boolean clearApiKey) {
        @Override
        public String toString() {
            return "ConfigurationRequest[modelName=" + modelName
                    + ", apiKeyProvided=" + (apiKey != null && !apiKey.isBlank())
                    + ", clearApiKey=" + clearApiKey + "]";
        }
    }

    /**
     * 测试连接结果。
     *
     * @param success 是否收到有效模型响应
     * @param message 面向 UI 的安全提示
     */
    @RegisterForReflection
    public record ConnectionTestResponse(boolean success, String message) {
    }

    /**
     * 配置 API 稳定错误。
     *
     * @param code 稳定错误码
     * @param message 不包含 Provider 原始响应的安全错误提示
     */
    @RegisterForReflection
    public record ConfigurationError(String code, String message) {
    }
}
