package com.db117.learnagent.agent.tool;

import com.db117.learnagent.agent.application.TutorContext;
import com.db117.learnagent.execution.ExecutionResult;
import com.db117.learnagent.execution.TypeScriptCompileResult;
import com.db117.learnagent.execution.TypeScriptDiagnostic;
import com.db117.learnagent.execution.TypeScriptTestResult;
import com.db117.learnagent.practice.application.PracticeRuntimeService;
import com.db117.learnagent.workspace.application.WorkspaceApplicationService;
import com.db117.learnagent.workspace.application.WorkspaceManager;
import com.db117.learnagent.workspace.domain.LearningWorkspace;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** TutorAgent 的最小 Workspace/Execution 工具集；不提供任意 shell 或 Domain 写入能力。 */
@Dependent
public final class TutorWorkspaceTools {
    private final WorkspaceManager workspaces;
    private final WorkspaceApplicationService workspaceAccess;
    private final PracticeRuntimeService practiceRuntime;

    @Inject
    public TutorWorkspaceTools(
            WorkspaceManager workspaces,
            WorkspaceApplicationService workspaceAccess,
            PracticeRuntimeService practiceRuntime) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces must not be null");
        this.workspaceAccess = Objects.requireNonNull(workspaceAccess, "workspaceAccess must not be null");
        this.practiceRuntime = Objects.requireNonNull(practiceRuntime, "practiceRuntime must not be null");
    }

    @Tool(
            name = "list_files",
            description = "列出当前 TypeScript Learning Workspace 内的文件路径；只能读取当前学习 Workspace。",
            readOnly = true)
    public String listFiles(TutorContext context) {
        LearningWorkspace workspace = learningWorkspace(context);
        try {
            List<String> files = workspaces.listFiles(workspace).stream()
                    .map(file -> file.path())
                    .toList();
            return files.isEmpty() ? "Workspace 为空。" : String.join("\n", files);
        } catch (IOException error) {
            throw new IllegalStateException("无法列出 Workspace 文件", error);
        }
    }

    @Tool(
            name = "read_file",
            description = "读取当前 TypeScript Learning Workspace 内一个 UTF-8 文本文件。",
            readOnly = true)
    public String readFile(
            TutorContext context,
            @ToolParam(name = "path", description = "Workspace 内 POSIX 相对路径") String path) {
        LearningWorkspace workspace = learningWorkspace(context);
        try {
            return workspaces.readFile(workspace, path).content();
        } catch (IOException error) {
            throw new IllegalStateException("无法读取 Workspace 文件", error);
        }
    }

    @Tool(
            name = "write_file",
            description = "写入当前 TypeScript Learning Workspace 内一个 UTF-8 文本文件；只能写 Workspace 内路径。")
    public String writeFile(
            TutorContext context,
            @ToolParam(name = "path", description = "Workspace 内 POSIX 相对路径") String path,
            @ToolParam(name = "content", description = "要写入的完整 UTF-8 文本") String content) {
        LearningWorkspace workspace = learningWorkspace(context);
        try {
            com.db117.learnagent.workspace.domain.WorkspaceFile file = workspaces.writeFile(workspace, path, content);
            return "已写入 " + file.path() + "（" + file.size() + " bytes）。";
        } catch (IOException error) {
            throw new IllegalStateException("无法写入 Workspace 文件", error);
        }
    }

    @Tool(
            name = "initialize_npm_project",
            description = "在当前 Learning Workspace 内创建项目目录并执行固定 npm init -y；不接受 shell 参数。")
    public String initializeNpmProject(
            TutorContext context,
            @ToolParam(name = "project_path", description = "Workspace 内 POSIX 项目目录") String projectPath) {
        ExecutionResult result = practiceRuntime.initializeNpmProject(learningWorkspace(context), projectPath);
        return executionResult("initialize_npm_project", result);
    }

    @Tool(
            name = "install_typescript",
            description = "在指定 Workspace 子项目内固定执行 npm install --save-dev typescript；安装脚本被禁用。")
    public String installTypeScript(
            TutorContext context,
            @ToolParam(name = "project_path", description = "Workspace 内 POSIX 项目目录") String projectPath) {
        ExecutionResult result = practiceRuntime.installTypeScript(learningWorkspace(context), projectPath);
        return executionResult("install_typescript", result);
    }

    @Tool(
            name = "compile_project",
            description = "在指定 Workspace 子项目内固定执行 npx tsc，并按 tsconfig.json 生成 JavaScript；返回诊断。")
    public String compileProject(
            TutorContext context,
            @ToolParam(name = "project_path", description = "Workspace 内 POSIX 项目目录") String projectPath) {
        return compileResult(practiceRuntime.compileProject(learningWorkspace(context), projectPath));
    }

    @Tool(
            name = "compile",
            description = "在当前 TypeScript Learning Workspace 执行固定的 tsc --noEmit，并返回诊断。",
            readOnly = true)
    public String compile(TutorContext context) {
        return compileResult(practiceRuntime.compile(learningWorkspace(context)));
    }

    @Tool(
            name = "run_tests",
            description = "在当前 TypeScript Learning Workspace 执行固定的 vitest run，并返回测试摘要。",
            readOnly = true)
    public String runTests(TutorContext context) {
        return testResult(practiceRuntime.runTests(learningWorkspace(context)));
    }

    @Tool(
            name = "run_program",
            description = "在当前 Learning Workspace 内通过 node 运行一个脚本；不接受 shell 命令。")
    public String runProgram(
            TutorContext context,
            @ToolParam(name = "script_path", description = "Workspace 内 POSIX 相对脚本路径") String scriptPath,
            @ToolParam(name = "arguments", description = "传给脚本的普通参数", required = false)
            List<String> arguments) {
        ExecutionResult result = practiceRuntime.runProgram(learningWorkspace(context), scriptPath, arguments);
        return executionResult("run_program", result);
    }

    private LearningWorkspace learningWorkspace(TutorContext context) {
        if (context == null || !"LEARNING".equals(context.workspace().kind())) {
            throw new IllegalStateException("Practice 工具只在 LEARNING Session 中可用");
        }
        return workspaceAccess.learningWorkspace(Long.parseLong(context.workspace().id()));
    }

    private static String compileResult(TypeScriptCompileResult result) {
        StringBuilder output = new StringBuilder(executionResult("compile", result.execution()));
        if (!result.diagnostics().isEmpty()) {
            output.append("diagnostics:\n");
            for (TypeScriptDiagnostic diagnostic : result.diagnostics()) {
                output.append(formatDiagnostic(diagnostic)).append('\n');
            }
        }
        return output.toString();
    }

    private static String testResult(TypeScriptTestResult result) {
        return executionResult("run_tests", result.execution())
                + "test_count: " + result.testCount() + "\n"
                + "tests_passed: " + result.passed() + "\n";
    }

    private static String executionResult(String operation, ExecutionResult result) {
        return operation + "_passed: " + result.success() + "\n"
                + "exit_code: " + result.exitCode() + "\n"
                + "summary:\n" + result.summary();
    }

    private static String formatDiagnostic(TypeScriptDiagnostic diagnostic) {
        return diagnostic.severity() + " " + diagnostic.file() + ":"
                + diagnostic.line() + ":" + diagnostic.column() + " "
                + diagnostic.code() + " " + diagnostic.message();
    }
}
