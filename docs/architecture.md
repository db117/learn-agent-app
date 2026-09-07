# 第一阶段架构

```text
React + TypeScript + Vite
          │ HTTP / SSE
          ▼
Tauri 2 shell ── starts/stops/status of native sidecar
          │ 127.0.0.1:18080
          ▼
Spring Boot 4.1.1 native backend
  API → TutorAgentService → ADK Runner/LlmAgent/Session/Event/Tool
                  │
                  ├─ SpringAiLlm → Spring AI ChatModel → OpenAI
                  └─ JdbcClient → SQLite
```

前端负责界面展示和 SSE 消费。Rust 只负责桌面进程生命周期。Java 负责 Agent
行为、持久化和 API 契约。后端运行时使用内存中的 ADK 会话服务，并使用 SQLite
保存持久化应用记录。

固定的本地边界是 `127.0.0.1:18080`；MVP 有意不提供应用认证或授权。消息会先
保存，然后启动异步 ADK 运行。每个 ADK 事件都会保存并发布给会话的 SSE 监听器。
最终 Assistant 文本也会作为消息保存，运行状态标记为 `COMPLETED` 或 `FAILED`。

## 有意保留的边界

- ADK 是唯一的 Agent 运行时和工具执行器。
- Spring AI 仅负责提供商接入；不使用 Spring AI Agent、记忆、RAG 或工作流 API。
- `llm/infrastructure` 包含 ADK 到 Spring AI 的转换边界。
- SQLite 是第一阶段唯一的数据库。
- Native Image 是生产后端形态，不随包提供 JRE。

`src/features/monaco` 目录是为后续阶段保留的、已有文档说明的占位目录，不是隐藏
的编辑器实现。

## 第二阶段 Learning Journey

```text
React Learning Journey
  ├─ Welcome / profile
  ├─ Diagnostic / assessment answers
  └─ Path + Lesson + Tutor
       │ HTTP / SSE
       ▼
LearningController → Learning Engine → JdbcClient → SQLite
                                  ├─ LLM-generated curriculum and Question bank
                                  ├─ Assessment / Attempt history
                                  └─ deterministic Score / Path / Progress
                                                   ▲
                     LlmCurriculumGenerator / LlmCodingEvaluator / DiagnosticPlanner
                                (Spring AI provider boundary only)
```

课程目录和题库在运行时以 SQLite 为唯一读取来源。应用启动只创建表，不加载固定课程；
用户提交目标语言创建新 Journey 时，LLM 为这个 Journey 独立生成技能和 Lesson 内容，Java
校验后 insert-only 写入 SQLite，并通过 `learning_journey_skill` 建立专属关联。同一 Journey
从 SQLite 恢复，不同 Journey 即使目标语言相同也不会共享技能。诊断或技能评估再按需选择或
生成 Question。Question 使用 insert-only，退役记录写入 `question_retirement`，所以已有
Assessment 与 Attempt 不会因题目从活动题库移除而失去历史引用。评估题集创建后固定在
`assessment_question` 中，Retry 复用同一题集；创建新 Journey 需要 LLM 可用才能生成课程。
