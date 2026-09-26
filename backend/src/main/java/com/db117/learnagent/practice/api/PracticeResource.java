package com.db117.learnagent.practice.api;

import com.db117.learnagent.execution.ExecutionResult;
import com.db117.learnagent.execution.TypeScriptCompileResult;
import com.db117.learnagent.execution.TypeScriptDiagnostic;
import com.db117.learnagent.execution.TypeScriptTestResult;
import com.db117.learnagent.learning.application.JourneyApplicationService;
import com.db117.learnagent.learning.application.LearningRequestException;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.practice.application.PracticeRuntimeService;
import com.db117.learnagent.practice.domain.*;
import com.db117.learnagent.workspace.application.WorkspaceApplicationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Practice Runtime API；只返回编译、测试和验证的稳定摘要，不返回宿主机路径。 */
@Path("/api/journeys/{journeyId}/practice")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public final class PracticeResource {
    private static final String TYPESCRIPT_STARTER_SOURCE = "export {};";
    private static final String CODE_TASK_TYPE = "CODE";
    private static final String CHOICE_TASK_TYPE = "CHOICE";
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
    @Path("/compile")
    public CompileResponse compile(@PathParam("journeyId") long journeyId) {
        return CompileResponse.from(runtime.compile(workspaces.learningWorkspace(journeyId)));
    }

    @POST
    @Path("/tests")
    public TestResponse tests(@PathParam("journeyId") long journeyId) {
        return TestResponse.from(runtime.runTests(workspaces.learningWorkspace(journeyId)));
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

    /** 返回当前 LearnUnit 已保存的选择题安全投影；不返回正确选项或作答证据。 */
    @GET
    @Path("/choice")
    public ChoiceResponse choice(@PathParam("journeyId") long journeyId) {
        LearningJourney learningJourney = journeys.learningJourneyFor(journeyId);
        com.db117.learnagent.learning.domain.LearningPathItem currentItem = learningJourney.currentItem();
        if (currentItem == null) {
            return ChoiceResponse.empty(false);
        }
        LearnUnit unit = learningJourney.learnUnit(currentItem.learnUnitCode());
        Long learnUnitId = Objects.requireNonNull(unit.id(), "persisted LearnUnit id must not be null");
        List<PracticeTask> tasks = practiceTasks.findByLearnUnit(learningJourney.id(), learnUnitId);
        boolean codeVerified = hasVerifiedCodeTask(tasks);
        return tasks.stream()
                .filter(value -> value.status() == PracticeTaskStatus.OPEN)
                .filter(value -> CHOICE_TASK_TYPE.equals(value.type()))
                .findFirst()
                .map(value -> ChoiceResponse.from(value, codeVerified))
                .orElseGet(() -> ChoiceResponse.empty(codeVerified));
    }

    /** 提交当前 LearnUnit 的选择题；代码阶段通过后才允许写入选择题证据。 */
    @POST
    @Path("/tasks/{taskId}/choice/verify")
    public ChoiceVerifyResponse verifyChoice(
            @PathParam("journeyId") long journeyId,
            @PathParam("taskId") long taskId,
            ChoiceRequest request) {
        LearningJourney learningJourney = journeys.learningJourneyFor(journeyId);
        com.db117.learnagent.learning.domain.LearningPathItem currentItem = learningJourney.currentItem();
        if (currentItem == null) {
            throw LearningRequestException.conflict("LEARNING_JOURNEY_COMPLETED", "学习路径已经完成");
        }
        if (request == null || request.optionId() == null || request.optionId().isBlank()) {
            throw LearningRequestException.badRequest("INVALID_PRACTICE_ANSWER", "optionId 不能为空");
        }
        LearnUnit unit = learningJourney.learnUnit(currentItem.learnUnitCode());
        long learnUnitId = requireLearnUnitId(unit);
        List<PracticeTask> tasks = practiceTasks.findByLearnUnit(learningJourney.id(), learnUnitId);
        if (!hasVerifiedCodeTask(tasks)) {
            throw LearningRequestException.conflict(
                    "CODE_PRACTICE_REQUIRED", "请先完成代码练习，再回答选择题");
        }
        PracticeTask task = practiceTasks.findById(taskId)
                .filter(value -> value.journeyId() == learningJourney.id())
                .orElseThrow(() -> new NotFoundException("PracticeTask 不存在"));
        requireChoiceTask(task);
        if (task.status() != PracticeTaskStatus.OPEN || task.learnUnitId() != learnUnitId) {
            throw LearningRequestException.conflict("CHOICE_TASK_NOT_OPEN", "选择题不属于当前可答的 LearnUnit");
        }
        ChoiceQuestion question = Objects.requireNonNull(task.choiceQuestion(), "choice task question must not be null");
        if (!question.hasOption(request.optionId())) {
            throw LearningRequestException.badRequest("INVALID_PRACTICE_ANSWER", "optionId 不属于当前选择题");
        }

        boolean correct = question.isCorrect(request.optionId());
        PracticeEvidence evidence = new PracticeEvidence(
                false,
                false,
                0,
                false,
                RuntimeResult.NOT_RUN,
                List.of(),
                correct ? Instant.now() : null,
                correct);
        PracticeTask saved = practiceTasks.save(task.recordAttempt(
                PracticeAttempt.submit(evidence, Instant.now())));
        return ChoiceVerifyResponse.from(saved, evidence, learningJourney, false);
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
        requireCodeTask(task);
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
                .filter(value -> CODE_TASK_TYPE.equals(value.type()))
                .findFirst()
                .orElseGet(() -> practiceTasks.save(PracticeTask.create(
                        learningJourney.id(),
                        learnUnitId,
                        learningJourney.languagePackId(),
                        CODE_TASK_TYPE,
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
                .filter(value -> CODE_TASK_TYPE.equals(value.type()))
                .flatMap(value -> value.attempts().stream())
                .max(java.util.Comparator.comparing(PracticeAttempt::submittedAt)
                        .thenComparing(value -> value.id() == null ? 0L : value.id()));
    }

    private static long requireLearnUnitId(LearnUnit unit) {
        return Objects.requireNonNull(unit.id(), "persisted LearnUnit id must not be null");
    }

    private static void requireCodeTask(PracticeTask task) {
        if (!CODE_TASK_TYPE.equals(task.type())) {
            throw LearningRequestException.badRequest("NOT_CODE_TASK", "该 PracticeTask 不是编码题");
        }
    }

    private static void requireChoiceTask(PracticeTask task) {
        if (!CHOICE_TASK_TYPE.equals(task.type())) {
            throw LearningRequestException.badRequest("NOT_CHOICE_TASK", "该 PracticeTask 不是选择题");
        }
    }

    private static boolean hasVerifiedCodeTask(List<PracticeTask> tasks) {
        return tasks.stream()
                .filter(value -> CODE_TASK_TYPE.equals(value.type()))
                .anyMatch(value -> value.status() == PracticeTaskStatus.VERIFIED);
    }

    /**
     * 编译结果及可定位的 TypeScript 诊断。
     *
     * @param success tsc 进程是否成功
     * @param exitCode tsc 退出码
     * @param summary 有限 stdout/stderr 摘要
     * @param durationMillis 执行耗时毫秒数
     * @param diagnostics 编译器诊断列表
     */
    public record CompileResponse(
            boolean success,
            int exitCode,
            String summary,
            long durationMillis,
            List<TypeScriptDiagnostic> diagnostics) {
        static CompileResponse from(TypeScriptCompileResult result) {
            ExecutionResult execution = result.execution();
            return new CompileResponse(
                    execution.success(), execution.exitCode(), execution.summary(),
                    execution.duration().toMillis(), result.diagnostics());
        }
    }

    /**
     * Vitest 结果及实际测试数量。
     *
     * @param success Vitest 进程是否成功
     * @param exitCode Vitest 退出码
     * @param summary 有限 stdout/stderr 摘要
     * @param durationMillis 执行耗时毫秒数
     * @param testCount 实际执行的测试数量
     * @param passed 是否满足“进程成功且至少一个测试”的 Runtime 条件
     */
    public record TestResponse(
            boolean success,
            int exitCode,
            String summary,
            long durationMillis,
            int testCount,
            boolean passed) {
        static TestResponse from(TypeScriptTestResult result) {
            ExecutionResult execution = result.execution();
            return new TestResponse(
                    execution.success(), execution.exitCode(), execution.summary(),
                    execution.duration().toMillis(), result.testCount(), result.passed());
        }
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

    /**
     * 当前 LearnUnit 的已保存选择题；只投影学习者可以看到的题干和选项。
     *
     * @param available 当前 LearnUnit 是否存在可展示的已保存选择题
     * @param codeVerified 当前 LearnUnit 的编码题是否已经通过
     * @param taskId 选择题 PracticeTask 的稳定主键；没有题目时为空
     * @param title 选择题标题；没有题目时为空
     * @param prompt 学习者可见的题干；没有题目时为空
     * @param options 学习者可见的选项；不包含正确选项标记
     */
    public record ChoiceResponse(
            boolean available,
            boolean codeVerified,
            Long taskId,
            String title,
            String prompt,
            List<ChoiceOption> options) {
        public ChoiceResponse {
            options = List.copyOf(options == null ? List.of() : options);
        }

        static ChoiceResponse empty(boolean codeVerified) {
            return new ChoiceResponse(false, codeVerified, null, null, null, List.of());
        }

        static ChoiceResponse from(PracticeTask task, boolean codeVerified) {
            ChoiceQuestion question = Objects.requireNonNull(task.choiceQuestion(), "choice task question must not be null");
            return new ChoiceResponse(
                    true,
                    codeVerified,
                    Objects.requireNonNull(task.id(), "persisted task id must not be null"),
                    task.title(),
                    question.prompt(),
                    question.options());
        }
    }

    /**
     * 选择题答案请求。
     *
     * @param optionId 学习者提交的选项 ID；只接受当前题目公开的选项
     */
    public record ChoiceRequest(String optionId) {
    }

    /**
     * 选择题验证后的稳定摘要；不返回正确选项本身。
     *
     * @param taskId 选择题 PracticeTask 的稳定主键
     * @param status 验证后的任务状态
     * @param verified 本次 Evidence 是否满足选择题验证策略
     * @param choiceCorrect 本次答案是否正确
     * @param learningJourneyStatus 本次选择题验证后的 LearningJourney 状态；验证本身不修改进度
     * @param currentLearnUnitCode 验证后当前 LearnUnit；路径完成后为空
     * @param advanced 本次验证是否推进路径；Tutor 评估确认前始终为 false
     */
    public record ChoiceVerifyResponse(
            long taskId,
            String status,
            boolean verified,
            boolean choiceCorrect,
            String learningJourneyStatus,
            String currentLearnUnitCode,
            boolean advanced) {
        static ChoiceVerifyResponse from(
                PracticeTask task,
                PracticeEvidence evidence,
                LearningJourney after,
                boolean advanced) {
            return new ChoiceVerifyResponse(
                    Objects.requireNonNull(task.id(), "persisted task id must not be null"),
                    task.status().name(),
                    evidence.isVerified(task.verificationPolicy()),
                    evidence.choiceCorrect(),
                    after.status().name(),
                    after.currentItem() == null ? null : after.currentItem().learnUnitCode(),
                    advanced);
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
