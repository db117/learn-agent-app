package com.db117.learnagent.transfer.api;

import com.db117.learnagent.config.ModelConfiguration;
import com.db117.learnagent.config.ModelConfigurationService;
import com.db117.learnagent.config.ModelConfigurationService.ConfigurationView;
import com.db117.learnagent.config.OpenAIProtocol;
import com.db117.learnagent.transfer.application.JourneyTransferService;
import com.db117.learnagent.transfer.application.JourneyTransferService.ImportResult;
import com.db117.learnagent.transfer.application.JourneyTransferService.OverwriteConfirmationRequired;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** 用户主动触发的文件导出和导入 API。 */
@Path("/api/transfer")
@Produces(MediaType.APPLICATION_JSON)
public class DataTransferResource {
    private final JourneyTransferService journeys;
    private final ModelConfigurationService modelConfiguration;

    public DataTransferResource(
            JourneyTransferService journeys,
            ModelConfigurationService modelConfiguration) {
        this.journeys = journeys;
        this.modelConfiguration = modelConfiguration;
    }

    @GET
    @Path("/journeys")
    @Produces("application/zip")
    public Response exportJourneys() {
        return Response.ok(journeys.exportAll())
                .header("Content-Disposition", "attachment; filename=learn-agent-journeys.zip")
                .header("Cache-Control", "no-store")
                .build();
    }

    @POST
    @Path("/journeys/preview")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response previewJourneys(byte[] archive) {
        try {
            return Response.ok(journeys.preview(archive)).build();
        } catch (IllegalArgumentException error) {
            return invalid(error.getMessage());
        }
    }

    @POST
    @Path("/journeys")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response importJourneys(
            byte[] archive,
            @QueryParam("replaceConflicts") @DefaultValue("false") boolean replaceConflicts) {
        try {
            ImportResult result = journeys.importAll(archive, replaceConflicts);
            return Response.ok(result).build();
        } catch (OverwriteConfirmationRequired error) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new TransferError("OVERWRITE_CONFIRMATION_REQUIRED", error.getMessage()))
                    .build();
        } catch (IllegalArgumentException error) {
            return invalid(error.getMessage());
        }
    }

    /** 配置只在此用户主动导出接口返回明文 API Key。 */
    @GET
    @Path("/model-config")
    public Response exportModelConfiguration() {
        ModelConfiguration configuration = modelConfiguration.exportForTransfer();
        return Response.ok(ModelConfigurationTransfer.from(configuration))
                .header("Cache-Control", "no-store")
                .build();
    }

    @PUT
    @Path("/model-config")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response importModelConfiguration(ModelConfigurationTransfer request) {
        if (request == null || request.formatVersion() != 1) {
            return invalid("模型配置文件内容无效");
        }
        try {
            ConfigurationView result = modelConfiguration.save(
                    request.modelName(), request.protocol(), request.baseUrl(), request.apiKey(), true);
            return Response.ok(result).build();
        } catch (IllegalArgumentException error) {
            return invalid(error.getMessage());
        }
    }

    private static Response invalid(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new TransferError("INVALID_TRANSFER_FILE", message))
                .build();
    }

    /** 配置迁移文件的内容；密钥只通过此显式迁移通道读写。
     *
     * @param formatVersion 模型配置迁移文件版本
     * @param modelName OpenAI 兼容服务中的模型名称
     * @param protocol 模型调用时使用的 OpenAI 兼容协议
     * @param baseUrl OpenAI 兼容服务的基础地址
     * @param apiKey API Key 明文；此传输文件不加密
     */
    @RegisterForReflection
    public record ModelConfigurationTransfer(
            int formatVersion,
            String modelName,
            OpenAIProtocol protocol,
            String baseUrl,
            String apiKey) {
        public static ModelConfigurationTransfer from(ModelConfiguration configuration) {
            return new ModelConfigurationTransfer(1,
                    configuration.modelName(), configuration.protocol(),
                    configuration.baseUrl(), configuration.apiKey());
        }

        @Override
        public String toString() {
            return "ModelConfigurationTransfer[formatVersion=" + formatVersion + ", modelName=" + modelName
                    + ", protocol=" + protocol
                    + ", apiKeyConfigured=" + (apiKey != null && !apiKey.isBlank()) + "]";
        }
    }

    /** 不包含内部异常详情的迁移错误。
     *
     * @param code 稳定的迁移错误码
     * @param message 可以展示给用户的错误说明
     */
    @RegisterForReflection
    public record TransferError(String code, String message) {
    }
}
