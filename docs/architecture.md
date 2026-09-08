# 架构

```text
React + TypeScript + Vite
          │ HTTP / SSE
          ▼
Tauri 2 shell ── starts/stops/status of native sidecar
          │ 127.0.0.1:18080
          ▼
Spring Boot 4.0.0 backend
  API → TutorAgentService → SAA ReactAgent / Graph Core / Tool
                  │
                  ├─ Spring AI ChatModel → OpenAI
                  └─ JdbcClient → SQLite
```

Rust 只负责桌面进程生命周期，React 只消费框架无关的 HTTP/SSE DTO 和事件。Java 负责
TutorAgent、Learning Engine、workflow 状态、持久化和 API 契约。SAA Graph Core 表达
Java 节点、Agent 节点和条件路由；Graph State 只属于当前执行，SQLite 保存长期事实。
每次 Learning Action 由 `LearningWorkflowGraph` 创建并执行一次短生命周期 Graph，完成后立即
结束；Pass/Retry/Skip/Next/Completed 等结果由 Java 节点写入 `workflow_transition`。

## 运行时边界

- 后端只保留一个 Spring AI Alibaba `ReactAgent`，Agent capability 使用框架 Skill registry。
- Spring AI 只负责 ChatModel 提供商接入；提供商适配集中在 `llm/infrastructure`。
- `LearnUnit` 是按 Journey 由 LLM 生成并持久化的教学知识，不进入 Skill registry。
- Learning Engine 用 Java 规则决定分数、Pass、Retry、Skip、Next 和 Journey 完成状态。
- SQLite 是唯一持久化数据库，使用 Spring `JdbcClient`；Question 只能新增或 soft delete。
- Native Image 是生产后端形态，不随包提供 JRE。

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
只为该 Journey 生成 LearnUnit，Java 校验后 insert-only 写入 SQLite，并通过
`learning_journey_learn_unit` 建立关联。同一 Journey 重启后只恢复 SQLite 数据；不同
Journey 即使目标语言相同也不会共享课程或题目。诊断和 LearnUnit 评估需要题目时才由 LLM
选择或生成，Assessment 创建后固定题集，Retry 复用原题集。
