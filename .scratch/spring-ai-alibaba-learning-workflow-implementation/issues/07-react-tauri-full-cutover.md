# 07: React/Tauri 全链路切换与协议回归

**What to build:** 桌面用户可以从 Welcome/Resume 走完目标语言、Diagnostic、LearnUnit、Tutor、Assessment、Pass/Retry/Skip 和恢复流程，前端通过稳定的 HTTP/SSE 协议使用新的后端 Agent runtime。

**Blocked by:** 03: TutorAgent 与 Agent Skill 的 LearnUnit 上下文对话; 06: Learning Workflow 的 Pass/Retry/Skip/Next/Completed

**Status:** complete

- [x] React 页面覆盖新学习流程，并通过框架无关 API DTO 与事件类型通信。
- [x] SSE 正确处理 text delta、tool start、tool end、error 和 complete 事件。
- [x] Welcome/Resume 能恢复 Journey、当前 LearnUnit、Path、Attempt 和 Tutor Session。
- [x] Tauri Rust 继续负责 Native backend sidecar 的启动、停止和固定 backend 地址。
- [x] 前端、后端 HTTP/SSE、SQLite 和 Tauri 集成检查全部通过。
- [x] React 和 Rust 不包含 Agent 编排或 Spring AI Alibaba 内部类型依赖。

**Evidence:** `pnpm check` passes TypeScript, ESLint, Vite, Cargo and backend tests; SSE now persists and emits `complete`.
