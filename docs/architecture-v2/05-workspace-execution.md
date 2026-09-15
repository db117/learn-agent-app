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

Execution Tool：`compile`、`run_tests`、`run_program`、`format`、`lint`。

Domain Tool：`get_learning_context`、`get_current_unit`、`get_progress`、`verify_practice` 等。

不要默认提供 `executeShell(String command)`。

第一语言 TypeScript 使用：Node、pnpm、tsc、Vitest。
