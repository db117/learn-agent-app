package com.db117.learnagent.practice.application;

import com.db117.learnagent.execution.ExecutionEnvironment;
import com.db117.learnagent.execution.ExecutionOperation;
import com.db117.learnagent.execution.ExecutionRequest;
import com.db117.learnagent.execution.ExecutionResult;
import com.db117.learnagent.execution.TypeScriptCompileResult;
import com.db117.learnagent.execution.TypeScriptCompiler;
import com.db117.learnagent.execution.TypeScriptTestResult;
import com.db117.learnagent.execution.TypeScriptTestRunner;
import com.db117.learnagent.learning.application.LearningRequestException;
import com.db117.learnagent.practice.domain.PracticeAttempt;
import com.db117.learnagent.practice.domain.PracticeEvidence;
import com.db117.learnagent.practice.domain.PracticeTask;
import com.db117.learnagent.practice.domain.PracticeTaskRepository;
import com.db117.learnagent.practice.domain.RuntimeResult;
import com.db117.learnagent.practice.domain.VerificationPolicy;
import com.db117.learnagent.workspace.application.WorkspaceManager;
import com.db117.learnagent.workspace.domain.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Practice Runtime 的应用边界；执行结果先保持在 Runtime，验证时才写入 Practice Domain。 */
@ApplicationScoped
public final class PracticeRuntimeService {
    private final ExecutionEnvironment executionEnvironment;
    private final TypeScriptCompiler compiler;
    private final TypeScriptTestRunner testRunner;
    private final WorkspaceManager workspaceManager;
    private final PracticeTaskRepository practiceTasks;

    @Inject
    public PracticeRuntimeService(
            ExecutionEnvironment executionEnvironment,
            TypeScriptCompiler compiler,
            TypeScriptTestRunner testRunner,
            WorkspaceManager workspaceManager,
            PracticeTaskRepository practiceTasks) {
        this.executionEnvironment = Objects.requireNonNull(
                executionEnvironment, "executionEnvironment must not be null");
        this.compiler = Objects.requireNonNull(compiler, "compiler must not be null");
        this.testRunner = Objects.requireNonNull(testRunner, "testRunner must not be null");
        this.workspaceManager = Objects.requireNonNull(workspaceManager, "workspaceManager must not be null");
        this.practiceTasks = Objects.requireNonNull(practiceTasks, "practiceTasks must not be null");
    }

    /** 在当前 Workspace 中执行 TypeScript 编译；编译器参数由 Language Pack/ExecutionEnvironment 固定。 */
    public TypeScriptCompileResult compile(Workspace workspace) {
        return compiler.compile(requireWorkspace(workspace), List.of());
    }

    /** 在当前 Workspace 中执行 Vitest；测试路径为空表示运行项目默认测试集合。 */
    public TypeScriptTestResult runTests(Workspace workspace) {
        return testRunner.run(requireWorkspace(workspace), List.of());
    }

    /** 通过受限 ExecutionEnvironment 运行 Workspace 内的 Node 脚本。 */
    public ExecutionResult runProgram(Workspace workspace, String scriptPath, List<String> arguments) {
        var args = new ArrayList<String>();
        args.add(Objects.requireNonNull(scriptPath, "scriptPath must not be null"));
        if (arguments != null) {
            args.addAll(arguments);
        }
        return executionEnvironment.execute(
                requireWorkspace(workspace), new ExecutionRequest(ExecutionOperation.RUN_PROGRAM, args));
    }

    /** 执行编译和测试，并把固定验收策略需要的客观证据追加到 PracticeTask。 */
    public PracticeVerification verify(PracticeTask task, Workspace workspace) {
        var currentTask = Objects.requireNonNull(task, "task must not be null");
        requireSupportedPolicy(currentTask.verificationPolicy());
        var currentWorkspace = requireWorkspace(workspace);
        var compile = compile(currentWorkspace);
        var tests = runTests(currentWorkspace);
        var submittedFiles = submittedFiles(currentWorkspace);
        var candidate = new PracticeEvidence(
                compile.execution().success(),
                tests.passed(),
                tests.testCount(),
                false,
                RuntimeResult.NOT_RUN,
                submittedFiles,
                null);
        var verifiedAt = currentTask.verificationPolicy().accepts(candidate)
                && changedFromStarter(currentTask, currentWorkspace)
                ? Instant.now()
                : null;
        var evidence = new PracticeEvidence(
                candidate.compilePassed(),
                candidate.testsPassed(),
                candidate.testCount(),
                candidate.lintPassed(),
                candidate.runtimeResult(),
                candidate.submittedFiles(),
                verifiedAt);
        var saved = practiceTasks.save(currentTask.recordAttempt(
                PracticeAttempt.submit(evidence, Instant.now())));
        return new PracticeVerification(saved, compile, tests, evidence);
    }

    /** Step 6 的 TypeScript 练习必须先改变初始源码，避免 starter 测试本身伪造完成证据。 */
    private boolean changedFromStarter(PracticeTask task, Workspace workspace) {
        if (task.starterTemplate().isBlank()) {
            return true;
        }
        try {
            return !task.starterTemplate().equals(
                    workspaceManager.readFile(workspace, "src/index.ts").content());
        } catch (IOException error) {
            return false;
        }
    }

    /** 当前 Step 5 只具备 compile/tests 的固定验收能力；不为未实现的检查伪造 Evidence。 */
    private static void requireSupportedPolicy(VerificationPolicy policy) {
        var unsupported = new ArrayList<String>();
        if (policy.requireLint()) {
            unsupported.add("lint");
        }
        if (policy.requireRuntime()) {
            unsupported.add("runtime");
        }
        if (!unsupported.isEmpty()) {
            throw LearningRequestException.badRequest(
                    "UNSUPPORTED_VERIFICATION_POLICY",
                    "当前 Practice Runtime 不支持验证项: " + String.join(", ", unsupported));
        }
    }

    private List<String> submittedFiles(Workspace workspace) {
        try {
            return workspaceManager.listFiles(workspace).stream()
                    .map(file -> file.path())
                    .toList();
        } catch (IOException error) {
            throw new IllegalStateException("Unable to list practice files", error);
        }
    }

    private static Workspace requireWorkspace(Workspace workspace) {
        return Objects.requireNonNull(workspace, "workspace must not be null");
    }

    /**
     * 一次验证的 Runtime 结果和最终写入的 Evidence。
     *
     * @param task 追加 Attempt 后的 PracticeTask 快照
     * @param compile 本次编译结果
     * @param tests 本次测试结果
     * @param evidence 按 PracticeTask 策略计算出的客观证据
     */
    public record PracticeVerification(
            PracticeTask task,
            TypeScriptCompileResult compile,
            TypeScriptTestResult tests,
            PracticeEvidence evidence) {
        public PracticeVerification {
            task = Objects.requireNonNull(task, "task must not be null");
            compile = Objects.requireNonNull(compile, "compile must not be null");
            tests = Objects.requireNonNull(tests, "tests must not be null");
            evidence = Objects.requireNonNull(evidence, "evidence must not be null");
        }
    }
}
