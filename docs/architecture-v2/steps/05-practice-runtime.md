# Step 5 — Practice Runtime

## 执行原则

- 不兼容旧实现。
- 不引入临时架构。
- 不提前实现后续阶段。
- 不为了当前任务修改 Architecture Contract。
- 每个任务必须有测试。
- 每一步完成后系统必须保持可运行。

## 目标与范围

实现 read/write/list file、compile、run_tests、run_program、diagnostics；加入 ExecutionEnvironment 和
LocalExecutionEnvironment。

完整链路：用户写 TS → compile 失败 → Tutor 观察 diagnostic → Hint → 用户修复 → tests pass → Domain 写 PracticeEvidence。

**DoD：**至少一个错误 TypeScript Practice 可以完整跑通。达到 M1。
