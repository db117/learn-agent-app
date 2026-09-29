package com.db117.learnagent.practice.api;

import com.db117.learnagent.execution.ExecutionResult;
import com.db117.learnagent.learning.application.JourneyApplicationService;
import com.db117.learnagent.learning.application.LearningRequestException;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.practice.application.PracticeRuntimeService;
import com.db117.learnagent.practice.domain.PracticeAssessment;
import com.db117.learnagent.practice.domain.PracticeAssessmentRepository;
import com.db117.learnagent.practice.domain.PracticeAssessmentVerdict;
import com.db117.learnagent.practice.domain.PracticeAttempt;
import com.db117.learnagent.practice.domain.PracticeEvidence;
import com.db117.learnagent.practice.domain.PracticeTask;
import com.db117.learnagent.practice.domain.PracticeTaskRepository;
import com.db117.learnagent.practice.domain.PracticeTaskStatus;
import com.db117.learnagent.practice.domain.VerificationPolicy;
import com.db117.learnagent.workspace.application.WorkspaceApplicationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Practice API；只返回受限脚本运行和验证的稳定摘要，不返回宿主机路径。 */
@Path("/api/journeys/{journeyId}/practice")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public final class PracticeResource {
    private static final String TYPESCRIPT_STARTER_SOURCE = "export {};";
    private final WorkspaceApplicationService workspaces;
    private final PracticeRuntimeService runtime;
    private final PracticeTaskRepository practiceTasks;
    private final PracticeAssessmentRepository assessments;
    private final JourneyApplicationService journeys;

    @Inject
    public PracticeResource(
            WorkspaceApplicationService workspaces,
            PracticeRuntimeService runtime,
            PracticeTaskRepository practiceTasks,
            PracticeAssessmentRepository assessments,
            JourneyApplicationService journeys) {
        this.workspaces = workspaces;
        this.runtime = runtime;
        this.practiceTasks = practiceTasks;
        this.assessments = assessments;
        this.journeys = journeys;
    }

    @POST
    @Path("/run")
    public ProgramResponse run(
            @PathParam("journeyId") long journeyId,
            ProgramRequest request) {
        if (request == null || request.scriptPath() == null || request.scriptPath().isBlank()) {
            throw new BadRequestException("scriptPath must not be blank");
        }
        return ProgramResponse.from(runtime.runProgram(
                workspaces.learningWorkspace(journeyId), request.scriptPath(), request.arguments()));
    }

    @POST
    @Path("/tasks/{taskId}/verify")
    public VerifyResponse verify(
            @PathParam("journeyId") long journeyId,
            @PathParam("taskId") long taskId) {
        LearningJourney learningJourney = journeys.learningJourneyFor(journeyId);
        PracticeTask task = practiceTasks.findById(taskId)
                .filter(value -> value.journeyId() == learningJourney.id())
                .orElseThrow(() -> new NotFoundException("PracticeTask 不存在"));
        requireCurrentTask(learningJourney, task);
        PracticeRuntimeService.PracticeVerification result = runtime.verify(task, workspaces.learningWorkspace(journeyId));
        return VerifyResponse.from(result.task(), result.evidence(), learningJourney, learningJourney);
    }

    /** 验证当前 LearnUnit；首次验证时按当前路径项创建最小 PracticeTask。 */
    @POST
    @Path("/verify")
    public VerifyResponse verifyCurrent(@PathParam("journeyId") long journeyId) {
        LearningJourney learningJourney = journeys.learningJourneyFor(journeyId);
        com.db117.learnagent.learning.domain.LearningPathItem currentItem = learningJourney.currentItem();
        if (currentItem == null) {
            throw LearningRequestException.conflict("LEARNING_JOURNEY_COMPLETED", "学习路径已经完成");
        }
        LearnUnit unit = learningJourney.learnUnit(currentItem.learnUnitCode());
        Long learnUnitId = Objects.requireNonNull(unit.id(), "persisted LearnUnit id must not be null");
        List<PracticeTask> candidates = practiceTasks.findByLearnUnit(learningJourney.id(), learnUnitId);
        PracticeTask task = candidates.stream()
                .filter(value -> value.status() == PracticeTaskStatus.OPEN)
                .findFirst()
                .orElseGet(() -> practiceTasks.save(PracticeTask.create(
                        learningJourney.id(),
                        learnUnitId,
                        learningJourney.languagePackId(),
                        "练习：" + unit.title(),
                        unit.practiceInstruction(),
                        1,
                        TYPESCRIPT_STARTER_SOURCE,
                        new VerificationPolicy(true, true, false, false),
                        Instant.now())));
        PracticeRuntimeService.PracticeVerification result = runtime.verify(task, workspaces.learningWorkspace(journeyId));
        return VerifyResponse.from(result.task(), result.evidence(), learningJourney, learningJourney);
    }

    @GET
    @Path("/assessment")
    public AssessmentResponse assessment(@PathParam("journeyId") long journeyId) {
        LearningJourney learningJourney = journeys.learningJourneyFor(journeyId);
        com.db117.learnagent.learning.domain.LearningPathItem currentItem = learningJourney.currentItem();
        if (currentItem == null) {
            return AssessmentResponse.empty();
        }
        long learnUnitId = requireLearnUnitId(learningJourney.learnUnit(currentItem.learnUnitCode()));
        PracticeAssessment assessment = assessments.findLatest(learningJourney.id(), learnUnitId).orElse(null);
        if (assessment == null) {
            return AssessmentResponse.empty();
        }
        PracticeTask assessedTask = practiceTasks.findById(assessment.practiceTaskId())
                .filter(task -> task.journeyId() == learningJourney.id())
                .orElseThrow(() -> new IllegalStateException("assessment task is missing"));
        PracticeAttempt attempt = assessedTask.attempts().stream()
                .filter(value -> Objects.equals(value.id(), assessment.practiceAttemptId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("assessment attempt is missing"));
        String currentDigest = runtime.contentDigest(workspaces.learningWorkspace(journeyId));
        boolean latestAttempt = latestCodeAttempt(learningJourney, learnUnitId)
                .map(value -> Objects.equals(value.id(), attempt.id()))
                .orElse(false);
        return AssessmentResponse.from(assessment, attempt.evidence(),
                !latestAttempt || !assessment.workspaceDigest().equals(currentDigest));
    }

    @POST
    @Path("/assessments/{assessmentId}/accept")
    public AcceptAssessmentResponse acceptAssessment(
            @PathParam("journeyId") long journeyId,
            @PathParam("assessmentId") long assessmentId) {
        LearningJourney learningJourney = journeys.learningJourneyFor(journeyId);
        PracticeAssessment assessment = assessments.findById(assessmentId)
                .filter(value -> value.journeyId() == learningJourney.id())
                .orElseThrow(() -> new NotFoundException("PracticeAssessment 不存在"));
        boolean alreadyAccepted = learningJourney.pathItems().stream()
                .anyMatch(item -> Objects.equals(item.assessmentId(), assessmentId));
        if (alreadyAccepted) {
            return AcceptAssessmentResponse.from(learningJourney);
        }
        com.db117.learnagent.learning.domain.LearningPathItem currentItem = learningJourney.currentItem();
        if (currentItem == null) {
            throw LearningRequestException.conflict("LEARNING_JOURNEY_COMPLETED", "学习路径已经完成");
        }
        long learnUnitId = requireLearnUnitId(learningJourney.learnUnit(currentItem.learnUnitCode()));
        if (assessment.learnUnitId() != learnUnitId
                || assessment.verdict() != PracticeAssessmentVerdict.READY) {
            throw LearningRequestException.conflict("ASSESSMENT_NOT_ACCEPTABLE", "当前评估不能推进当前 LearnUnit");
        }
        PracticeAssessment latest = assessments.findLatest(learningJourney.id(), learnUnitId).orElse(null);
        if (latest == null || !Objects.equals(latest.id(), assessment.id())) {
            throw LearningRequestException.conflict("ASSESSMENT_OUTDATED", "已有更新的 Tutor 评估，请查看最新结果");
        }
        PracticeTask assessedTask = practiceTasks.findById(assessment.practiceTaskId())
                .filter(task -> task.journeyId() == learningJourney.id())
                .orElseThrow(() -> new IllegalStateException("assessment task is missing"));
        PracticeAttempt attempt = assessedTask.attempts().stream()
                .filter(value -> Objects.equals(value.id(), assessment.practiceAttemptId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("assessment attempt is missing"));
        boolean latestAttempt = latestCodeAttempt(learningJourney, learnUnitId)
                .map(value -> Objects.equals(value.id(), attempt.id()))
                .orElse(false);
        String currentDigest = runtime.contentDigest(workspaces.learningWorkspace(journeyId));
        if (!latestAttempt || !assessment.workspaceDigest().equals(currentDigest)) {
            throw LearningRequestException.conflict("ASSESSMENT_STALE", "代码在检查后发生变化，请重新提交检查");
        }
        return AcceptAssessmentResponse.from(
                journeys.acceptPracticeAssessment(
                        journeyId,
                        currentItem.learnUnitCode(),
                        assessment.id(),
                        attempt.evidence().isVerified(assessedTask.verificationPolicy())));
    }

    private void requireCurrentTask(LearningJourney journey, PracticeTask task) {
        com.db117.learnagent.learning.domain.LearningPathItem current = journey.currentItem();
        if (current == null || task.learnUnitId() != requireLearnUnitId(journey.learnUnit(current.learnUnitCode()))) {
            throw LearningRequestException.conflict("PRACTICE_TASK_NOT_CURRENT", "编码题不属于当前 LearnUnit");
        }
    }

    private java.util.Optional<PracticeAttempt> latestCodeAttempt(LearningJourney journey, long learnUnitId) {
        return practiceTasks.findByLearnUnit(journey.id(), learnUnitId).stream()
                .flatMap(value -> value.attempts().stream())
                .max(java.util.Comparator.comparing(PracticeAttempt::submittedAt)
                        .thenComparing(value -> value.id() == null ? 0L : value.id()));
    }

    private static long requireLearnUnitId(LearnUnit unit) {
        return Objects.requireNonNull(unit.id(), "persisted LearnUnit id must not be null");
    }

    /**
     * 运行 Workspace 内 Node 脚本的请求。
     *
     * @param scriptPath Workspace 内的 POSIX 相对脚本路径
     * @param arguments 传给脚本的普通参数，不包含 shell 片段
     */
    public record ProgramRequest(
            String scriptPath,
            List<String> arguments) {
        public ProgramRequest {
            arguments = List.copyOf(arguments == null ? List.of() : arguments);
        }
    }

    /**
     * Node 脚本的有限执行摘要。
     *
     * @param success 进程是否成功
     * @param exitCode 进程退出码
     * @param summary 有限 stdout/stderr 摘要
     * @param durationMillis 执行耗时毫秒数
     */
    public record ProgramResponse(
            boolean success,
            int exitCode,
            String summary,
            long durationMillis) {
        static ProgramResponse from(ExecutionResult result) {
            return new ProgramResponse(
                    result.success(), result.exitCode(), result.summary(), result.duration().toMillis());
        }
    }

    /** 当前 Tutor 对最新编码提交的评估；stale 表示提交后代码或提交记录已变化。 */
    public record AssessmentResponse(
            boolean available,
            Long assessmentId,
            Long attemptId,
            PracticeAssessmentVerdict verdict,
            String rationale,
            boolean stale,
            boolean compilePassed,
            boolean testsPassed,
            int testCount) {
        static AssessmentResponse empty() {
            return new AssessmentResponse(false, null, null, null, null, false, false, false, 0);
        }

        static AssessmentResponse from(PracticeAssessment assessment, PracticeEvidence evidence, boolean stale) {
            return new AssessmentResponse(true, assessment.id(), assessment.practiceAttemptId(),
                    assessment.verdict(), assessment.rationale(), stale, evidence.compilePassed(),
                    evidence.testsPassed(), evidence.testCount());
        }
    }

    /** 用户确认 Tutor 的 READY 评估后返回新的路径位置。 */
    public record AcceptAssessmentResponse(
            String learningJourneyStatus,
            String currentLearnUnitCode,
            boolean accepted) {
        static AcceptAssessmentResponse from(LearningJourney journey) {
            return new AcceptAssessmentResponse(journey.status().name(),
                    journey.currentItem() == null ? null : journey.currentItem().learnUnitCode(), true);
        }
    }

    /**
     * PracticeTask 验证后公开的 Domain 摘要。
     *
     * @param taskId PracticeTask 的稳定主键
     * @param assessmentAttemptId 本次已保存代码提交的稳定主键，Tutor 评估必须引用该次提交
     * @param status 验证后的任务状态
     * @param verified 本次 Evidence 是否满足任务策略
     * @param compilePassed 编译是否通过
     * @param testsPassed 测试是否通过
     * @param testCount 实际测试数量
     * @param submittedFiles 本次验证涉及的文件路径
     * @param verifiedAt 通过验证时的时间；失败时为空
     * @param learningJourneyStatus 本次代码验证后的 LearningJourney 状态；验证本身不修改进度
     * @param currentLearnUnitCode 当前 LearningPathItem 对应的 LearnUnit；路径完成后为空
     * @param advanced 本次验证是否推进路径；Tutor 评估确认前始终为 false
     */
    public record VerifyResponse(
            long taskId,
            long assessmentAttemptId,
            String status,
            boolean verified,
            boolean compilePassed,
            boolean testsPassed,
            int testCount,
            List<String> submittedFiles,
            Instant verifiedAt,
            String learningJourneyStatus,
            String currentLearnUnitCode,
            boolean advanced) {
        static VerifyResponse from(
                PracticeTask task,
                PracticeEvidence evidence,
                LearningJourney before,
                LearningJourney after) {
            return new VerifyResponse(
                    Objects.requireNonNull(task.id(), "persisted task id must not be null"),
                    Objects.requireNonNull(task.attempts().get(task.attempts().size() - 1).id(),
                            "persisted practice attempt id must not be null"),
                    task.status().name(),
                    evidence.verifiedAt() != null,
                    evidence.compilePassed(),
                    evidence.testsPassed(),
                    evidence.testCount(),
                    evidence.submittedFiles(),
                    evidence.verifiedAt(),
                    after.status().name(),
                    after.currentItem() == null ? null : after.currentItem().learnUnitCode(),
                    !Objects.equals(
                            before.currentItem() == null ? null : before.currentItem().learnUnitCode(),
                            after.currentItem() == null ? null : after.currentItem().learnUnitCode()));
        }
    }

}
