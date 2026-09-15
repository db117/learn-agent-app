package com.db117.learnagent.agent.api;

import com.db117.learnagent.agent.application.TutorRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** 将 Tutor 业务错误映射为稳定 JSON；不透传内部异常和 Provider 文本。 */
@Provider
public class TutorExceptionMapper implements ExceptionMapper<TutorRequestException> {
    @Override
    public Response toResponse(TutorRequestException error) {
        return Response.status(error.status())
                .type(MediaType.APPLICATION_JSON)
                .entity(new TutorErrorResponse(error.code(), error.publicMessage()))
                .build();
    }
}
