# 项目规则

## 运行时边界

- 旧实现使用 Spring AI Alibaba Graph Core 和 `ReactAgent`；当前迁移目标使用 Spring Boot WebFlux 承载应用边界、AgentScope
  `HarnessAgent` 承载 Agent runtime，应用只保留一个 `TutorAgent`。
- 旧实现的 Spring AI `ChatModel` 只负责 provider seam；当前目标由 AgentScope OpenAI provider 承担模型接入，Agent
  编排、工具执行和事件流留在 Agent runtime 中。
- HTTP、SSE、SQLite 持久化和前端 DTO 使用框架无关的数据结构；不要把 AgentScope 或 SAA 内部消息类型泄漏到这些边界。
- `Skill` 表示 Agent capability，统一放在 `agent-skills` 并由 AgentScope Skill registry 加载；教学知识、课程内容和学习状态属于
  `LearnUnit` 与 Learning Engine。
- 工具注册给 AgentScope Agent，由 Agent runtime 执行；TutorAgent 不能直接修改学习分数、通过状态或学习路径。
- SQLite 是 MVP 唯一持久化数据库；当前目标继续使用 Spring JDBC/JdbcClient 和 Xerial JDBC，阻塞数据库操作不能占用 WebFlux
  event loop；不引入 R2DBC、JPA 或 Hibernate。
- Native Image 暂不属于当前迁移阶段的正式构建要求；当前正式目标是 macOS arm64 上的 Spring Boot WebFlux JVM，Native、其他平台和
  Native runtime hints 后置。
- Rust 只负责 Tauri 桌面壳和后端进程生命周期；Agent 和 Learning Engine 逻辑保留在 Java 中。

## Learning Core

- Learning Journey 是学习入口。学习者提交目标语言和学习目标后，LLM 为当前 Journey 按需生成独立的 `LearnUnit` 与教学内容，并保存到 SQLite；不同 Journey 不共享课程目录。
- Java Learning Engine 确定性控制评分、通过、重试、跳过、下一单元、路径和进度；TutorAgent 只负责教学对话。
- Question 运行时来源是 SQLite。题目定义只能新增或 soft delete；题干、答案、分值和评分规则不可更新。Assessment 只引用创建时固定的 Question，历史 Attempt 必须可读。
- Diagnostic 和 LearnUnit 评估可以由 LLM 选题或生成题目，但 Java 必须校验结构和题型覆盖；LLM 不可用时直接报错，不回退到已有题库。
- 每次用户 Action 都是一次短生命周期 Agent 调用；Agent 不等待用户输入，状态迁移完成后持久化 workflow transition。

## 变更边界

- 保留固定后端地址 `127.0.0.1:18080`。
- MVP 目标包含一个 TutorAgent、AgentScope Harness、OpenAI provider、HTTP/SSE、SQLite 和 Tauri/React 壳。
- MVP 不包含应用认证、授权、Token、工作区沙箱、MCP、RAG、Monaco 实现或自动更新系统。
- 影响前端、Tauri 或后端完整构建的变更后运行 `pnpm check`；Native 检查不属于当前迁移验收，只有未来明确进入 Native 范围时才运行。

## Agent skills

### Issue tracker

本仓库使用 `.scratch/<feature>/` 下的本地 Markdown 文件管理需求、规格和任务。详见 `docs/agents/issue-tracker.md`。

### Triage labels

使用默认 triage 标签：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。详见
`docs/agents/triage-labels.md`。

### Domain docs

本仓库采用单一上下文布局，使用根目录 `CONTEXT.md` 和 `docs/adr/`。详见 `docs/agents/domain.md`。

### 当前 AgentScope 迁移范围补充

- 本轮迁移以 macOS arm64 上的 AgentScope + Spring Boot WebFlux JVM 新链路为正式目标；Native Image、Windows、Linux、其他
  macOS 架构和 Native Skill 资源适配全部后置，不属于当前 Definition of Done。
- Spring Boot WebFlux 负责 HTTP、SSE、SQLite、Learning Engine 和 Tauri 进程边界；AgentScope 是唯一 Agent runtime。SSE 使用
  WebFlux 原生流，SQLite/JDBC 等阻塞操作必须隔离到工作线程。
- CI 使用 Fake/Deterministic Model；macOS arm64 端到端验收使用真实 OpenAI。LLM 生成失败直接报错，不回退到旧题库或其他生成路径。
- 新链路完整跑通后删除旧 Spring AI/Spring AI Alibaba/ADK Agent Runtime 和 Adapter；不保留运行时回退，也不要求旧 SQLite 数据或
  schema 兼容。发现旧 schema 时明确报错并要求重新初始化。
- 当前验收不保存运行日志、Prompt、模型响应或密钥；只保留平台、版本、日期和通过/失败结果。
