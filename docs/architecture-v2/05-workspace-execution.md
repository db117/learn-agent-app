# Workspace and Execution

## 用户目录

```text
~/.learn-agent/

config/
db/

agent/
  AGENTS.md
  MEMORY.md
  memory/
  skills/
  knowledge/
  subagents/

journeys/{journeyId}/
  workspace/
  artifacts/

projects/{projectId}/
  workspace/
  artifacts/
```

## 三类 Workspace

- Agent Workspace：Agent 能力与长期上下文。
- Learning Workspace：Practice 代码。
- Project Workspace：完整项目代码。

三者不能混用。

## ExecutionEnvironment

从第一天建立稳定抽象：

```java
public interface ExecutionEnvironment {
    ExecutionResult execute(Workspace workspace, ExecutionRequest request);
}
```

实现：

```text
LocalExecutionEnvironment
SandboxExecutionEnvironment
```

上层业务只依赖接口。

## Tools

Workspace Tool：`list_files`、`read_file`、`write_file`、`search_files`。

Execution Tool：`initialize_npm_project`、`install_typescript`、`compile_project`、`compile`、`run_tests`、`run_program`、
`format`、`lint`。

Domain Tool：`get_learning_context`、`get_current_unit`、`get_progress`、`verify_practice` 等。

不要默认提供 `executeShell(String command)`。

第一语言 TypeScript 使用：Node、pnpm、tsc、Vitest。

Step 5 的当前 LocalExecutionEnvironment 将 compile、run_tests 和 Workspace 内固定脚本的 run_program
用于 Practice 验证，并为 TutorAgent 的可验证项目练习提供固定的 npm 初始化、TypeScript 安装和项目编译操作；这些操作
仍然不接受任意 shell。lint 尚未实现。Practice 验证不会把未执行的 lint/runtime 检查写入通过证据，要求这些检查的任务
会在应用边界被明确拒绝。Sandbox、Permission 和更完整的任务运行契约属于后续步骤。
