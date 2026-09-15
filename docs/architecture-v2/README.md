# learn-agent-app v2 Architecture Pack

本目录定义 `learn-agent-app` 的 **clean-slate v2 架构**。

目标不是兼容旧实现，而是重新构建一个真正的：

> **Agentic Programming Learning Environment**

最终能力：

- Learn：学习编程概念
- Practice：在真实 Workspace 中写代码、编译、测试、诊断
- Project：通过 Plan、Permission、MCP、Subagent 完成真实项目
- Skill：按需加载 Agent 工作方法
- Memory：记录长期偏好、背景与误区
- Sandbox：隔离代码执行
- Language Pack：可插拔语言支持
- Tauri + Quarkus + GraalVM Native Image：桌面交付

## 文档索引

### 总体设计

- `01-product-vision.md`
- `02-architecture-overview.md`
- `03-domain-model.md`
- `04-agent-runtime.md`
- `05-workspace-execution.md`
- `06-language-pack.md`
- `07-skills-memory.md`
- `08-plan-permission-mcp-subagent.md`
- `09-events-ui.md`
- `10-storage.md`

### 实施计划

- `11-roadmap.md`
- `steps/00-architecture-contract.md`
- `steps/01-runtime-skeleton.md`
- `steps/02-domain.md`
- `steps/03-agent-runtime.md`
- `steps/04-language-pack-workspace.md`
- `steps/05-practice-runtime.md`
- `steps/06-learn-mode.md`
- `steps/07-skills-memory.md`
- `steps/08-sandbox-permission.md`
- `steps/09-project-plan.md`
- `steps/10-mcp.md`
- `steps/11-subagents.md`
- `steps/12-native-eval-polish.md`

### Codex

- `codex/execution-rules.md`
- `codex/task-template.md`
- `codex/definition-of-done.md`

### 可直接用于仓库的模板

- `templates/AGENTS.md`
- `templates/CONTEXT.md`

## 不可突破的原则

1. 不兼容旧架构，不保留历史包袱。
2. Domain State 与 Agent State 永久分离。
3. Learning Domain 是 score、mastery、completion、assessment 的唯一权威。
4. Agent 负责如何帮助用户，不直接篡改学习事实。
5. AgentScope 提供 Runtime；应用层不重复造 Session/Skill/Memory/Plan/Subagent Runtime。
6. Workspace 是核心概念。
7. Language Pack 是产品 Plugin；Skill 是 Agent Capability。
8. 所有执行受 Workspace、ExecutionEnvironment、Sandbox、Permission 约束。
9. 只有一个主 Agent：TutorAgent。
10. Subagent 只用于专业化和上下文隔离。
11. 每个阶段直接朝最终架构实现，不引入临时兼容层。
12. 每一步必须独立可运行、可测试。

## 最终技术栈

```text
Tauri 2
React + TypeScript
Monaco Editor

Quarkus
AgentScope Java 2.x
SQLite
GraalVM Native Image
```
