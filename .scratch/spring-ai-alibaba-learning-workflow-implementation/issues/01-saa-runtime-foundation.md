# 01: SAA 运行时对齐与 Agent/Graph 基础链路

**What to build:** 应用使用 Spring AI Alibaba 的兼容版本线运行一个 TutorAgent 和一次 Graph Action，同时保留现有后端对外接缝，作为后续 Learning Workflow 迁移的可验证基础。

**Blocked by:** None (can start immediately)

**Status:** complete

- [x] Spring Boot 4.0.0、Spring AI 2.0.0-M1、Spring AI Alibaba 2.0.0-M1.1 形成一致的可解析依赖组合。
- [x] 应用能够启动并创建单个 TutorAgent，接入现有 provider seam。
- [x] Graph 能执行一次包含 Java 节点、Agent 节点和条件路由的用户 Action。
- [x] 现有 HTTP/SSE、SQLite 和桌面 sidecar 的外部边界保持可用。
- [x] 不引入 Phase 0 Spike、Gate 或专用 fixture。

**Evidence:** `backend\mvnw.cmd -f backend\pom.xml test` 通过；SAA tool loop、Graph 路由和 Spring context 测试均通过。
