# 11: macOS arm64 完整验收与旧链路删除

**What to build:** 在 macOS arm64 上使用真实 OpenAI 完成完整用户旅程验收；验收通过后删除旧 Spring AI、Spring AI
Alibaba、ADK 和相关旧适配层，使新的 Spring Boot WebFlux + AgentScope 链路成为唯一运行时。

**Blocked by:** 10: Tauri + Spring Boot WebFlux JVM 桌面链路

**Status:** blocked by macOS arm64 + real OpenAI acceptance

- [ ] 手工验收覆盖启动、新 SQLite、Journey/LearnUnit 生成、无 Coding Question 分支、Diagnostic、Learning
  Path、TutorSession、Skill 展示、safe reasoning、SSE、AgentState 恢复、Assessment、Retry、Skip、Next 和 Journey 完成。
- [ ] 验收同时覆盖 provider 错误、LLM 生成失败、AgentState 恢复失败、旧 schema、SSE 重连、取消和错误终止行为。
- [x] 本机 CI-equivalent 的 Fake/Deterministic Model 测试、后端测试、前端检查和 Tauri 检查全部通过；Native 检查不作为门槛。
- [ ] 只在完整 E2E 通过后删除 Spring AI、Spring AI Alibaba、ADK、旧 Agent runtime、旧 adapter 和旧 event adapter。
- [ ] 删除后新链路不保留旧运行时 fallback，不要求旧数据、旧 schema 或旧 AgentState 兼容。
- [x] 更新架构、运行方式、学习领域和迁移文档，明确 Native、跨平台和 Workspace 属于后续工作。
- [x] 只保留平台、版本、日期和通过/失败状态等简短验收记录，不保存运行日志、Prompt、模型响应或密钥。

## Verification record

- Date: 2026-09-09
- Platform: Windows 11 x64; Java 21.0.12.1; Node 24.19.0; pnpm 11.19.0; Rust/Cargo 1.98.1.
- PASS: `pnpm check`, `pnpm desktop:smoke`, `pnpm package:desktop`.
- PASS: local CI-equivalent backend Fake/Deterministic Model and AgentScope WebFlux regression tests (76 tests, 0 failures).
- Audit: ADK runtime is absent; Spring AI/Spring AI Alibaba configuration, workflow bridge and LLM adapters remain, so legacy deletion is intentionally deferred.
- BLOCKED / NOT VERIFIED: macOS arm64 + real OpenAI full manual Journey, provider, restart, error and cutover acceptance; this Windows host cannot perform it.
- NOT RUN: Native checks; outside the current acceptance scope.
