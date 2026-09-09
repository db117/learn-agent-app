# 09: WebFlux SSE 重放、取消与错误处理

**What to build:** Tutor 调用和学习操作的 WebFlux SSE 流在重连、取消、失败和完成时具有稳定行为，用户能获得连续且安全的进度反馈。

**Blocked by:** 01: Spring Boot WebFlux TutorAgent 流主链路；02: AgentScope Skill 与安全事件展示

**Status:** resolved

- [x] 每个 TutorEvent 具有单调递增序号，发送前形成可重放的最小脱敏记录。
- [x] 客户端使用 Last-Event-ID 重连时只收到缺失事件，不重复或跳过已确认事件。
- [x] 事件流明确表达文本增量、reasoning 摘要、Skill/工具进度、完成、错误和取消；每次调用只有一个终止结果。
- [x] 客户端断开、用户取消或后端关闭时，取消信号能传递到 AgentScope stream 和相关模型调用。
- [x] provider、Skill、LLM 生成、AgentState 或结构校验失败都返回稳定错误事件，不伪装成成功或静默回退。
- [x] SSE 重放记录不保存运行日志、Prompt、完整模型响应、密钥、原始思维链或敏感调用参数。
- [x] 自动化测试覆盖 WebFlux 背压、序号、重放、取消、错误、完成和敏感内容过滤。

## Answer

- `TutorEvent` 使用 SQLite `event.sequence` 作为正向重放序号；EventHub 在回放期间暂存实时事件并按序合并，只发送 `Last-Event-ID` 之后的事件，SSE `id` 与序号一致。
- TutorAgent 流订阅改为可处置的 Reactor subscription，客户端断开、用户取消和 Spring 关闭都会取消 AgentScope/Harness/provider 流；取消、错误和完成均通过单一终止路径落库并发布。
- 错误只暴露稳定代码和安全文案；Skill/tool 参数、reasoning 原文、Prompt、密钥和原始异常不进入 TutorEvent JSON。React 保持 EventSource 自动重连并提供运行中取消按钮。
- 验证：issue 09 专项 E2E 8 个测试通过；完整 `pnpm check` 通过，后端 77 个测试全部通过；`git diff --check` 通过。

## Comments

- 2026-09-09：按当前 01/02 链路完成 Tutor SSE 重放、取消和错误处理。当前仓库的学习操作仍是 WebFlux 阻塞隔离的 HTTP 接口，没有独立学习 SSE 流；本 issue 未凭空新增该接口，也未修改 06-08 的学习业务契约。
