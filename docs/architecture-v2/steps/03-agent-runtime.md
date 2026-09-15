# Step 3 — Agent Runtime

## 执行原则

- 不兼容旧实现。
- 不引入临时架构。
- 不提前实现后续阶段。
- 不为了当前任务修改 Architecture Contract。
- 每个任务必须有测试。
- 每一步完成后系统必须保持可运行。

## 目标与范围

接 AgentScope Harness，建立唯一 TutorAgent、TutorContext、Session、Workspace binding 和 Event Projection。当前只实现 User →
TutorAgent → streamed response。

**DoD：**Tutor 可流式回答，Session 可恢复，UI 只收到 TutorEvent。
