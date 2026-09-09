# 09: WebFlux SSE 重放、取消与错误处理

**What to build:** Tutor 调用和学习操作的 WebFlux SSE 流在重连、取消、失败和完成时具有稳定行为，用户能获得连续且安全的进度反馈。

**Blocked by:** 01: Spring Boot WebFlux TutorAgent 流主链路；02: AgentScope Skill 与安全事件展示

**Status:** ready-for-agent

- [ ] 每个 TutorEvent 具有单调递增序号，发送前形成可重放的最小脱敏记录。
- [ ] 客户端使用 Last-Event-ID 重连时只收到缺失事件，不重复或跳过已确认事件。
- [ ] 事件流明确表达文本增量、reasoning 摘要、Skill/工具进度、完成、错误和取消；每次调用只有一个终止结果。
- [ ] 客户端断开、用户取消或后端关闭时，取消信号能传递到 AgentScope stream 和相关模型调用。
- [ ] provider、Skill、LLM 生成、AgentState 或结构校验失败都返回稳定错误事件，不伪装成成功或静默回退。
- [ ] SSE 重放记录不保存运行日志、Prompt、完整模型响应、密钥、原始思维链或敏感调用参数。
- [ ] 自动化测试覆盖 WebFlux 背压、序号、重放、取消、错误、完成和敏感内容过滤。
