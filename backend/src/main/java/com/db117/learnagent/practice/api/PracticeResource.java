package com.db117.learnagent.practice.api;

import com.db117.learnagent.execution.ExecutionResult;
import com.db117.learnagent.execution.TypeScriptCompileResult;
import com.db117.learnagent.execution.TypeScriptDiagnostic;
import com.db117.learnagent.execution.TypeScriptTestResult;
import com.db117.learnagent.learning.application.JourneyApplicationService;
import com.db117.learnagent.learning.application.LearningRequestException;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.practice.application.PracticeRuntimeService;
import com.db117.learnagent.practice.domain.PracticeEvidence;
import com.db117.learnagent.practice.domain.PracticeTask;
import com.db117.learnagent.practice.domain.PracticeTaskRepository;
import com.db117.learnagent.practice.domain.PracticeTaskStatus;
import com.db117.learnagent.practice.domain.VerificationPolicy;
import com.db117.learnagent.workspace.application.WorkspaceApplicationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
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
    private final WorkspaceApplicationService workspaces;
    private final PracticeRuntimeService runtime;
    private final PracticeTaskRepository practiceTasks;
    private final JourneyApplicationService journeys;

    @Inject
    public PracticeResource(
            WorkspaceApplicationService workspaces,
            PracticeRuntimeService runtime,
            PracticeTaskRepository practiceTasks,
            JourneyApplicationService journeys) {
        this.workspaces = workspaces;
        this.runtime = runtime;
        this.practiceTasks = practiceTasks;
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

    @POST
    @Path("/tasks/{taskId}/verify")
    public VerifyResponse verify(
            @PathParam("journeyId") long journeyId,
            @PathParam("taskId") long taskId) {
        var learningJourney = journeys.learningJourneyFor(journeyId);
        var task = practiceTasks.findById(taskId)
                .filter(value -> value.journeyId() == learningJourney.id())
                .orElseThrow(() -> new NotFoundException("PracticeTask 不存在"));
        var result = runtime.verify(task, workspaces.learningWorkspace(journeyId));
        var updated = recordLearningProgress(journeyId, learningJourney, result);
        return VerifyResponse.from(result.task(), result.evidence(), learningJourney, updated);
    }

    /** 验证当前 LearnUnit；首次验证时按当前路径项创建最小 PracticeTask。 */
    @POST
    @Path("/verify")
    public VerifyResponse verifyCurrent(@PathParam("journeyId") long journeyId) {
        var learningJourney = journeys.learningJourneyFor(journeyId);
        var currentItem = learningJourney.currentItem();
        if (currentItem == null) {
            throw LearningRequestException.conflict("LEARNING_JOURNEY_COMPLETED", "学习路径已经完成");
        }
        var unit = learningJourney.learnUnit(currentItem.learnUnitCode());
        var learnUnitId = Objects.requireNonNull(unit.id(), "persisted LearnUnit id must not be null");
        var candidates = practiceTasks.findByLearnUnit(learningJourney.id(), learnUnitId);
        if (currentItem.practiceVerified()) {
            throw LearningRequestException.conflict("PRACTICE_ALREADY_VERIFIED", "当前单元的 Practice 已验证");
        }
        var task = candidates.stream()
                .filter(value -> value.status() == PracticeTaskStatus.OPEN)
                .findFirst()
                .orElseGet(() -> practiceTasks.save(PracticeTask.create(
                        learningJourney.id(),
                        learnUnitId,
                        learningJourney.languagePackId(),
                        "CODE",
                        "练习：" + unit.title(),
                        unit.objective(),
                        1,
                        TYPESCRIPT_STARTER_SOURCE,
                        new VerificationPolicy(true, true, false, false),
                        Instant.now())));
        var result = runtime.verify(task, workspaces.learningWorkspace(journeyId));
        var updated = recordLearningProgress(journeyId, learningJourney, result);
        return VerifyResponse.from(result.task(), result.evidence(), learningJourney, updated);
    }

    private LearningJourney recordLearningProgress(
            long journeyId, LearningJourney before, PracticeRuntimeService.PracticeVerification result) {
        if (!result.evidence().isVerified(result.task().verificationPolicy())) {
            return before;
        }
        var learnUnitCode = before.learnUnits().stream()
                .filter(unit -> Objects.equals(unit.id(), result.task().learnUnitId()))
                .map(unit -> unit.code())
                .findFirst()
                .orElseThrow(() -> new NotFoundException("PracticeTask 对应的 LearnUnit 不存在"));
        return journeys.recordPracticeVerified(journeyId, learnUnitCode);
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
            var execution = result.execution();
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
            var execution = result.execution();
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
     * PracticeTask 验证后公开的 Domain 摘要。
     *
     * @param taskId PracticeTask 的稳定主键
     * @param status 验证后的任务状态
     * @param verified 本次 Evidence 是否满足任务策略
     * @param compilePassed 编译是否通过
     * @param testsPassed 测试是否通过
     * @param testCount 实际测试数量
     * @param submittedFiles 本次验证涉及的文件路径
     * @param verifiedAt 通过验证时的时间；失败时为空
     * @param learningJourneyStatus 回写 Learning Domain 后的 LearningJourney 状态
     * @param currentLearnUnitCode 当前 LearningPathItem 对应的 LearnUnit；路径完成后为空
     * @param advanced 本次验证是否使路径推进到了下一个单元
     */
    public record VerifyResponse(
            long taskId,
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
