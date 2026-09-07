# 项目规则

## 运行时边界

- ADK 是主要的 Agent 运行时。Agent 代码使用 ADK 的 `LlmAgent`、`Runner`、`Session`、`Event` 和工具 API。
- Spring AI 仅作为 LLM 提供商抽象层。将其 `ChatModel` 集成保留在 `llm/infrastructure` 中。
- 提供商转换逻辑保留在 `llm/infrastructure` 中；`agent` 不得依赖 Spring AI 类型。
- 工具必须作为 ADK 工具保留。提供商适配器可以暴露工具定义，但由 ADK 负责执行。
- SQLite 是 MVP 唯一的持久化数据库，并直接使用 Spring JDBC/JdbcClient。
- 将 GraalVM Native Image 兼容性视为一等构建要求；生产后端是 Native 可执行文件，不随包携带 JRE。
- Rust 只负责 Tauri 桌面壳和后端进程生命周期；Agent 逻辑保留在 Java 中。

## 变更边界

- 第一阶段包含一个 TutorAgent、一个简单的 ADK 工具、一个 OpenAI 提供商、HTTP/SSE、SQLite，以及 Tauri/React 壳。
- 保留固定的后端地址 `127.0.0.1:18080`。
- MVP 明确不包含应用认证、授权、Token、工作区沙箱、MCP、RAG、Monaco 实现或自动更新系统。
- 前端、Tauri 或会影响完整构建的后端发生变更后，运行 `pnpm check`。Native Image 行为发生变更时，运行 `pnpm native:check`。

## Phase 2 Learning Core

- Learning Journey 是主入口；确定性评分、通过、路径和进度由 Java Learning Engine 控制，TutorAgent 只负责教学对话。
- 用户提出目标语言；每个新 Journey 的技能、Lesson 内容和 Question 再由 LLM 按需生成。同一 Journey 从 SQLite 恢复，不同 Journey 不共享课程；不依赖固定课程资源或启动 seed。
- Question 的运行时来源是 SQLite。题目定义只允许新增或 soft delete；不得更新题干、答案、分值或评分规则。Assessment 只引用不可变 Question，历史 Attempt 必须可读。
- 题集在创建时持久化并固定；诊断和技能评估都由 LLM 按需选题或生成新题，Java 必须校验已有题目不可变、每个 Skill 至少包含 MC 和 Coding。应用启动不初始化 Question；LLM 不可用时只能从已有 SQLite 题库确定性回退。
