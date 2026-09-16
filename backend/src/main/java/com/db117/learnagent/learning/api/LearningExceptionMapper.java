package com.db117.learnagent.learning.api;

import com.db117.learnagent.learning.application.LearningRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** 将 Learner/Journey 业务错误映射为稳定 JSON。 */
@Provider
public class LearningExceptionMapper implements ExceptionMapper<LearningRequestException> {
    @Override
    public Response toResponse(LearningRequestException error) {
        return Response.status(error.status())
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(error.code(), error.publicMessage()))
                .build();
    }

    public record ErrorResponse(String code, String message) {
    }
}
