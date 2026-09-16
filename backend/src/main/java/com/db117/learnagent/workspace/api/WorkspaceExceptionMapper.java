package com.db117.learnagent.workspace.api;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class WorkspaceExceptionMapper implements ExceptionMapper<WorkspaceRequestException> {
    @Override
    public Response toResponse(WorkspaceRequestException error) {
        return Response.status(error.status())
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(error.code(), error.getMessage()))
                .build();
    }

    public record ErrorResponse(String code, String message) {
    }
}
