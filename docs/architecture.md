# 架构

```text
React + TypeScript + Vite
          │ framework-neutral HTTP / SSE
          ▼
Tauri 2 shell ── starts/stops/status of JVM process
          │ 127.0.0.1:18080
          ▼
Spring Boot 4.0.0 + WebFlux JVM
  ├─ SessionController → TutorAgentService
  │       → AgentScope HarnessAgent → AgentScope OpenAI Model
  │       ├─ AgentScope Skill repository / tools
  │       └─ SqliteAgentStateStore → JdbcClient → SQLite
  ├─ LearningController → Java Learning Engine → JdbcClient → SQLite
  │       ├─ Journey-scoped LearnUnit / Question
  │       ├─ fixed Assessment / Attempt history
  │       └─ deterministic Score / Path / Workflow facts
  ├─ LlmCurriculum/Diagnostic/Coding → AgentScope Model
  └─ ProgressService → Java deterministic workflow routing
```

Rust 只负责 JVM 进程生命周期，React 只消费项目自有 DTO 和 `TutorEvent`。Java 负责
HTTP、SSE、Learning Engine、SQLite、AgentScope TutorAgent 和边界适配。

## 当前迁移状态

AgentScope `HarnessAgent` 是 Tutor HTTP/SSE 的实际运行时，AgentState 使用新的 SQLite
表，Learning Engine 负责分数、通过、Retry、Skip、Next、路径和 Journey 完成。`LearnUnit`
是 Journey-scoped 教学知识；AgentScope `Skill` 是工程能力，两者没有领域关系。

AgentScope `HarnessAgent` 是唯一 Agent runtime，AgentScope OpenAI provider 是唯一模型
接入；学习内容生成、诊断选题和 Coding 评分直接调用 AgentScope `Model`。学习 workflow
使用 Java 确定性路由，工具只保留 AgentScope 注解，不存在旧 runtime 或运行时 fallback。

## 持久化与桌面边界

- HTTP、SSE、SQLite 和前端 DTO 不暴露 AgentScope 内部消息类型。
- AgentScope 是 Tutor 的目标运行时；Learning Engine 不由 Agent 直接修改分数、通过状态或路径。
- SQLite 是唯一 durable database，使用 Xerial JDBC 和 Spring `JdbcClient`；JDBC、事务和
  AgentState 等阻塞操作必须在 WebFlux event loop 外执行。
- 发现旧 schema 或旧 AgentState 时不迁移、不覆盖、不回退，要求使用新数据库路径。
- JVM JAR 是当前桌面后端形态；应用包不提供 JRE，运行环境需要 Java 21。

## Learning Journey

```text
React Welcome / Resume
  ├─ language + learner profile
  ├─ Diagnostic / fixed Assessment
  └─ Path + current LearnUnit + Tutor + Assessment
                     │ HTTP / SSE
                     ▼
LearningController → Learning Engine → JdbcClient → SQLite
                                      ├─ Journey-scoped LLM LearnUnit
                                      ├─ fixed Question / Attempt history
                                      └─ deterministic Score / Path / Progress
```

应用启动只创建表，不加载固定课程或 Question。用户提交目标语言创建 Journey 时，LLM
只为该 Journey 生成 LearnUnit 和必要题目；Java 校验后 insert-only 写入 SQLite，并通过
`learning_journey_learn_unit` 建立关联。同一 Journey 重启后只恢复 SQLite 数据；不同
Journey 即使目标语言相同也不会共享课程或题目。Assessment 创建后固定题集，Retry 复用
原题集；LLM 失败、结构非法或恢复失败均明确报错，不创建空上下文、不使用 fallback。
