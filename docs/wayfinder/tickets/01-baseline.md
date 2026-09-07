# 现有 Phase 1 基线与边界

Status: closed  
Parent: [Phase 2 Learning Core map](../phase-2-learning-core-map.md)

## Question

Phase 2 应从什么现有实现和运行时边界继续演进？

## Resolution

保留现有 Tauri 2/React/Vite、Rust sidecar 生命周期、Spring Boot HTTP/SSE、Google ADK `LlmAgent`/`Runner`/`Session`、Spring AI provider seam、JdbcClient + SQLite。固定 `127.0.0.1:18080`；现有 `src-tauri/icons/icon.png` 用户改动不触碰。基线 `pnpm check` 通过。
