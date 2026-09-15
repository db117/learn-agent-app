# Step 12 — Native + Eval + Polish

## 执行原则

- 不兼容旧实现。
- 不引入临时架构。
- 不提前实现后续阶段。
- 不为了当前任务修改 Architecture Contract。
- 每个任务必须有测试。
- 每一步完成后系统必须保持可运行。

## 目标与范围

集中处理 GraalVM reachability/resources/startup/binary size，建立 Tutor/Skill/Practice/Runtime
Eval，并完成签名、更新、异常处理、Provider UI 与打包。

**DoD：**最终产物 `learn-agent.app`，用户无需额外安装 Java。
