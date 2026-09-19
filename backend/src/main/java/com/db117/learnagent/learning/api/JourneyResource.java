package com.db117.learnagent.learning.api;

import com.db117.learnagent.learning.application.JourneyApplicationService;
import com.db117.learnagent.learning.application.LearningRequestException;
import com.db117.learnagent.workspace.application.WorkspaceApplicationService;
import com.db117.learnagent.workspace.application.WorkspaceInitializationException;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

/** Learner/Journey 引导 API；UI 只操作用户目标，不直接接触 AgentScope。 */
@Path("/api")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class JourneyResource {
    private final JourneyApplicationService journeys;
    private final WorkspaceApplicationService workspaces;

    public JourneyResource(
            JourneyApplicationService journeys,
            WorkspaceApplicationService workspaces) {
        this.journeys = journeys;
        this.workspaces = workspaces;
    }

    @GET
    @Path("/bootstrap")
    public OnboardingResponse bootstrap() {
        try {
            var workspace = workspaces.ensureCurrentLearningWorkspace()
                    .map(WorkspaceDescriptor::from)
                    .orElse(null);
            return OnboardingResponse.from(journeys.snapshot(), workspace);
        } catch (WorkspaceInitializationException error) {
            throw LearningRequestException.internal(
                    "WORKSPACE_INITIALIZATION_FAILED", "无法初始化学习 Workspace");
        }
    }

    @PUT
    @Path("/learner")
    public LearnerResponse saveLearner(LearnerRequest request) {
        if (request == null) {
            throw com.db117.learnagent.learning.application.LearningRequestException.badRequest(
                    "INVALID_LEARNER", "backgroundSummary 不能为空");
        }
        return LearnerResponse.from(journeys.saveLearner(request.backgroundSummary()));
    }

    @POST
    @Path("/journeys")
    public JourneyResponse createJourney(JourneyRequest request) {
        if (request == null) {
            throw com.db117.learnagent.learning.application.LearningRequestException.badRequest(
                    "INVALID_JOURNEY", "goalDescription 不能为空");
        }
        return JourneyResponse.from(journeys.createJourney(request.goalDescription()));
    }

    @POST
    @Path("/journeys/{journeyId}/select")
    public JourneyResponse selectJourney(@PathParam("journeyId") long journeyId) {
        return JourneyResponse.from(journeys.selectJourney(journeyId));
    }

    @POST
    @Path("/journeys/{journeyId}/confirm-plan")
    public JourneyResponse confirmPlan(
            @PathParam("journeyId") long journeyId,
            ConfirmPlanRequest request) {
        if (request == null) {
            throw LearningRequestException.badRequest("INVALID_PLAN", "请先生成规划草稿");
        }
        return JourneyResponse.from(journeys.confirmPlan(journeyId, request.plan()));
    }

    @GET
    @Path("/journeys/{journeyId}/learning")
    public LearningProgressResponse learning(@PathParam("journeyId") long journeyId) {
        return LearningProgressResponse.from(journeyId, journeys.learningJourneyFor(journeyId));
    }

}
