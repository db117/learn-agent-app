# Step 11 — Subagents

## 执行原则

- 不兼容旧实现。
- 不引入临时架构。
- 不提前实现后续阶段。
- 不为了当前任务修改 Architecture Contract。
- 每个任务必须有测试。
- 每一步完成后系统必须保持可运行。

## 目标与范围

加入 ResearchAgent、DebugAgent、ReviewAgent。三者独立 context、最小权限，不直接面对用户。

**DoD：**复杂 Bug 可由 Tutor 委派 DebugAgent，大量编译/测试日志留在子上下文，只把 root cause + evidence 返回 Tutor。达到 M2。
