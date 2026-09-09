# 01: Spring Boot WebFlux TutorAgent 流主链路

**What to build:** 在 macOS arm64 上运行一条新的 Spring Boot WebFlux JVM TutorAgent 链路，学习者可以从 React 发起 Tutor
请求并通过 SSE 收到文本回答。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] 使用 Java 21 + Spring Boot 4.0.0 + Spring WebFlux 启动新应用，并固定监听 `127.0.0.1:18080`。
- [x] 使用 AgentScope Java 2.x `HarnessAgent` 运行唯一的 `TutorAgent`，并接入 AgentScope OpenAI provider。
- [x] 使用 WebFlux 原生响应流返回项目自有的 `TutorEvent`，至少包含文本增量和终止事件。
- [x] HTTP/SSE 不直接暴露 AgentScope、Spring AI 或 Spring AI Alibaba 内部事件类型。
- [x] CI 使用 Fake/Deterministic Model 完成可重复的端到端文本流测试；旧 Agent runtime 暂不删除。
- [x] 不引入 Native、R2DBC、JPA、Hibernate 或第二套 Agent runtime。

## Answer

- 已完成 Spring Boot WebFlux + AgentScope HarnessAgent Tutor 主链路、项目自有 SSE 事件投影和固定端口 HTTP/SSE E2E。
- `pnpm check` 通过；提交：`4fae944 feat: migrate tutor stream to WebFlux AgentScope`。

## Comments

- 2026-09-09：完成实现、代码审查和全量验证。SSE 重放/取消、Skill 安全事件、SQLite AgentState、Tauri 和旧链路删除由后续 issue 负责。
