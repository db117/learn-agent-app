package com.db117.learnagent.agent.api;

import com.db117.learnagent.agent.application.TutorSessionService;
import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;
import org.reactivestreams.FlowAdapters;

/** Tutor API；只传输安全的 TutorEvent，不把 AgentScope raw event 暴露给 UI。 */
@Path("/api/tutor")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class TutorResource {
    private final TutorSessionService sessions;

    public TutorResource(TutorSessionService sessions) {
        this.sessions = sessions;
    }

    @POST
    @Path("/sessions")
    public TutorSessionResponse createSession(CreateTutorSessionRequest request) {
        return sessions.createSession(request);
    }

    @POST
    @Path("/sessions/{sessionId}/messages")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<TutorEvent> sendMessage(
            @PathParam("sessionId") String sessionId,
            SendTutorMessageRequest request) {
        return Multi.createFrom().publisher(
                FlowAdapters.toFlowPublisher(sessions.streamTurn(sessionId, request)));
    }

    @POST
    @Path("/sessions/{sessionId}/cancel")
    public TutorCancelResponse cancel(@PathParam("sessionId") String sessionId) {
        return sessions.cancel(sessionId);
    }
}
