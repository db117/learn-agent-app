# 项目规则

## 运行时边界

- Spring AI Alibaba Graph Core 和 `ReactAgent` 是唯一的 Agent runtime；应用只保留一个 `TutorAgent`。
- Spring AI `ChatModel` 只负责 provider seam；SAA Agent 的编排、工具执行和 Graph 节点留在 Agent runtime 中。
- HTTP、SSE、SQLite 持久化和前端 DTO 使用框架无关的数据结构；不要把 SAA 内部消息类型泄漏到这些边界。
- `Skill` 表示 Agent capability，统一放在 `agent-skills` 并由 SAA Skill registry 加载；教学知识、课程内容和学习状态属于 `LearnUnit` 与 Learning Engine。
- 工具注册给 SAA Agent，由 Agent runtime 执行；TutorAgent 不能直接修改学习分数、通过状态或学习路径。
- SQLite 是 MVP 唯一持久化数据库，直接使用 Spring JDBC/JdbcClient。
- GraalVM Native Image 是正式构建要求；生产后端是 Native 可执行文件，不随包携带 JRE。新增运行时类型时同步维护 runtime hints。
- Rust 只负责 Tauri 桌面壳和后端进程生命周期；Agent 和 Learning Engine 逻辑保留在 Java 中。

## Learning Core

- Learning Journey 是学习入口。学习者提交目标语言和学习目标后，LLM 为当前 Journey 按需生成独立的 `LearnUnit` 与教学内容，并保存到 SQLite；不同 Journey 不共享课程目录。
- Java Learning Engine 确定性控制评分、通过、重试、跳过、下一单元、路径和进度；TutorAgent 只负责教学对话。
- Question 运行时来源是 SQLite。题目定义只能新增或 soft delete；题干、答案、分值和评分规则不可更新。Assessment 只引用创建时固定的 Question，历史 Attempt 必须可读。
- Diagnostic 和 LearnUnit 评估可以由 LLM 选题或生成题目，但 Java 必须校验结构和题型覆盖；LLM 不可用时只能从已有 SQLite 题库确定性回退。
- 每次用户 Action 都是一次短生命周期 Graph/Agent 调用；Graph 不等待用户输入，状态迁移完成后持久化 workflow transition。

## 变更边界

- 保留固定后端地址 `127.0.0.1:18080`。
- MVP 包含一个 TutorAgent、SAA Graph、OpenAI provider、HTTP/SSE、SQLite 和 Tauri/React 壳。
- MVP 不包含应用认证、授权、Token、工作区沙箱、MCP、RAG、Monaco 实现或自动更新系统。
- 影响前端、Tauri 或后端完整构建的变更后运行 `pnpm check`；Native Image 行为变更且 GraalVM 可用时运行 `pnpm native:check`。
