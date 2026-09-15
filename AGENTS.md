# AGENTS.md

## 架构合同

本仓库是 clean-slate v2 实现。

1. 不为 v1 添加向后兼容。
2. Domain State 与 Agent State 必须保持分离。
3. Learning Domain 是 score、mastery、completion 和 assessment 的唯一权威来源。
4. AgentScope 负责 session、memory、skill、plan、MCP、permission 和 subagents 等 Runtime 能力。
5. Language Pack 是产品插件；Skill 是 Agent capability。
6. UI 不得直接消费 AgentScope raw event。
7. 任意 shell 执行默认禁止。
8. 代码执行必须通过 ExecutionEnvironment。
9. TutorAgent 是唯一面向用户的主 Agent。
10. Subagent 只用于专业化和上下文隔离。
11. 除非明确要求，不要实现路线图中的后续阶段。
12. 每个任务必须有明确、狭窄的修改范围和验证方式。

## 唯一依据

修改代码前读取 `docs/architecture-v2/` 中与任务相关的文档；执行规则见
`docs/architecture-v2/codex/execution-rules.md`。
