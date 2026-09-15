# Step 8 — Sandbox + Permission

## 执行原则

- 不兼容旧实现。
- 不引入临时架构。
- 不提前实现后续阶段。
- 不为了当前任务修改 Architecture Contract。
- 每个任务必须有测试。
- 每一步完成后系统必须保持可运行。

## 目标与范围

在既有 ExecutionEnvironment 下加入 SandboxExecutionEnvironment，并启用 ALLOW/ASK/DENY Permission。实现 Permission UI。

**DoD：**compile/test 默认允许，install dependency 询问，workspace 外写入和任意系统命令默认拒绝。
