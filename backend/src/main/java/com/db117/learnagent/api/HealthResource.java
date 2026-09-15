package com.db117.learnagent.api;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.sql.Connection;
import java.sql.SQLException;

@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
public class HealthResource {
    @Inject
    AgroalDataSource dataSource;

    @GET
    public Response health() {
        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("SELECT 1");
            return Response.ok(new HealthResponse("UP", "learn-agent-backend", "UP")).build();
        } catch (SQLException error) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new HealthResponse("DOWN", "learn-agent-backend", "DOWN"))
                    .build();
        }
    }

    /**
     * 健康检查响应。
     *
     * @param status 服务总体状态
     * @param service 服务名称
     * @param database 数据库连接状态
     */
    @RegisterForReflection
    public record HealthResponse(String status, String service, String database) {
    }
}
