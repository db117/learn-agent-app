package com.db117.learnagent.practice.api;

import com.db117.learnagent.execution.ExecutionResult;
import com.db117.learnagent.execution.TypeScriptCompileResult;
import com.db117.learnagent.execution.TypeScriptDiagnostic;
import com.db117.learnagent.execution.TypeScriptTestResult;
import com.db117.learnagent.practice.application.PracticeRuntimeService;
import com.db117.learnagent.practice.domain.PracticeEvidence;
import com.db117.learnagent.practice.domain.PracticeTask;
import com.db117.learnagent.practice.domain.PracticeTaskRepository;
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
    private final WorkspaceApplicationService workspaces;
    private final PracticeRuntimeService runtime;
    private final PracticeTaskRepository practiceTasks;

    @Inject
    public PracticeResource(
            WorkspaceApplicationService workspaces,
            PracticeRuntimeService runtime,
            PracticeTaskRepository practiceTasks) {
        this.workspaces = workspaces;
        this.runtime = runtime;
        this.practiceTasks = practiceTasks;
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
        var task = practiceTasks.findById(taskId)
                .filter(value -> value.journeyId() == journeyId)
                .orElseThrow(() -> new NotFoundException("PracticeTask 不存在"));
        var result = runtime.verify(task, workspaces.learningWorkspace(journeyId));
        return VerifyResponse.from(result.task(), result.evidence());
    }

    /** 编译结果及可定位的 TypeScript 诊断。 */
    public record CompileResponse(
            /** tsc 进程是否成功。 */
            boolean success,
            /** tsc 退出码。 */
            int exitCode,
            /** 有限 stdout/stderr 摘要。 */
            String summary,
            /** 执行耗时毫秒数。 */
            long durationMillis,
            /** 编译器诊断列表。 */
            List<TypeScriptDiagnostic> diagnostics) {
        static CompileResponse from(TypeScriptCompileResult result) {
            var execution = result.execution();
            return new CompileResponse(
                    execution.success(), execution.exitCode(), execution.summary(),
                    execution.duration().toMillis(), result.diagnostics());
        }
    }

    /** Vitest 结果及实际测试数量。 */
    public record TestResponse(
            /** Vitest 进程是否成功。 */
            boolean success,
            /** Vitest 退出码。 */
            int exitCode,
            /** 有限 stdout/stderr 摘要。 */
            String summary,
            /** 执行耗时毫秒数。 */
            long durationMillis,
            /** 实际执行的测试数量。 */
            int testCount,
            /** 是否满足“进程成功且至少一个测试”的 Runtime 条件。 */
            boolean passed) {
        static TestResponse from(TypeScriptTestResult result) {
            var execution = result.execution();
            return new TestResponse(
                    execution.success(), execution.exitCode(), execution.summary(),
                    execution.duration().toMillis(), result.testCount(), result.passed());
        }
    }

    /** 运行 Workspace 内 Node 脚本的请求。 */
    public record ProgramRequest(
            /** Workspace 内的 POSIX 相对脚本路径。 */
            String scriptPath,
            /** 传给脚本的普通参数，不包含 shell 片段。 */
            List<String> arguments) {
        public ProgramRequest {
            arguments = List.copyOf(arguments == null ? List.of() : arguments);
        }
    }

    /** Node 脚本的有限执行摘要。 */
    public record ProgramResponse(
            /** 进程是否成功。 */
            boolean success,
            /** 进程退出码。 */
            int exitCode,
            /** 有限 stdout/stderr 摘要。 */
            String summary,
            /** 执行耗时毫秒数。 */
            long durationMillis) {
        static ProgramResponse from(ExecutionResult result) {
            return new ProgramResponse(
                    result.success(), result.exitCode(), result.summary(), result.duration().toMillis());
        }
    }

    /** PracticeTask 验证后公开的 Domain 摘要。 */
    public record VerifyResponse(
            /** PracticeTask 的稳定主键。 */
            long taskId,
            /** 验证后的任务状态。 */
            String status,
            /** 本次 Evidence 是否满足任务策略。 */
            boolean verified,
            /** 编译是否通过。 */
            boolean compilePassed,
            /** 测试是否通过。 */
            boolean testsPassed,
            /** 实际测试数量。 */
            int testCount,
            /** 本次验证涉及的文件路径。 */
            List<String> submittedFiles,
            /** 通过验证时的时间；失败时为空。 */
            Instant verifiedAt) {
        static VerifyResponse from(PracticeTask task, PracticeEvidence evidence) {
            return new VerifyResponse(
                    Objects.requireNonNull(task.id(), "persisted task id must not be null"),
                    task.status().name(),
                    evidence.verifiedAt() != null,
                    evidence.compilePassed(),
                    evidence.testsPassed(),
                    evidence.testCount(),
                    evidence.submittedFiles(),
                    evidence.verifiedAt());
        }
    }
}
