# 11: macOS arm64 完整验收与旧链路删除

**What to build:** 在 macOS arm64 上使用真实 OpenAI 完成完整用户旅程验收；验收通过后删除旧 Spring AI、Spring AI
Alibaba、ADK 和相关旧适配层，使新的 Spring Boot WebFlux + AgentScope 链路成为唯一运行时。

**Blocked by:** 10: Tauri + Spring Boot WebFlux JVM 桌面链路

**Status:** ready-for-agent

- [ ] 手工验收覆盖启动、新 SQLite、Journey/LearnUnit 生成、无 Coding Question 分支、Diagnostic、Learning
  Path、TutorSession、Skill 展示、safe reasoning、SSE、AgentState 恢复、Assessment、Retry、Skip、Next 和 Journey 完成。
- [ ] 验收同时覆盖 provider 错误、LLM 生成失败、AgentState 恢复失败、旧 schema、SSE 重连、取消和错误终止行为。
- [ ] CI 的 Fake/Deterministic Model 测试、后端测试、前端检查和 Tauri 检查全部通过；Native 检查不作为门槛。
- [ ] 只在完整 E2E 通过后删除 Spring AI、Spring AI Alibaba、ADK、旧 Agent runtime、旧 adapter 和旧 event adapter。
- [ ] 删除后新链路不保留旧运行时 fallback，不要求旧数据、旧 schema 或旧 AgentState 兼容。
- [ ] 更新架构、运行方式、学习领域和迁移文档，明确 Native、跨平台和 Workspace 属于后续工作。
- [ ] 只保留平台、版本、日期和通过/失败状态等简短验收记录，不保存运行日志、Prompt、模型响应或密钥。
