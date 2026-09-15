# Step 1 — Runtime Skeleton

## 执行原则

- 不兼容旧实现。
- 不引入临时架构。
- 不提前实现后续阶段。
- 不为了当前任务修改 Architecture Contract。
- 每个任务必须有测试。
- 每一步完成后系统必须保持可运行。

## 目标与范围

建立最终技术栈最小骨架：Tauri 2 + React/TypeScript + Quarkus + SQLite + GraalVM Native-ready。

后端只实现 `/health`、SSE 基础端点、SQLite 连接、配置加载；前端只实现 App Shell、Backend status、SSE connection；Tauri 管理
backend lifecycle。

**DoD：**Tauri 启动后自动拉起 Quarkus，health 正常，SSE 正常，JVM dev mode 与 native build 均可验证。
