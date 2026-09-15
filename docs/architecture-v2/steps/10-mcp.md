# Step 10 — MCP

## 执行原则

- 不兼容旧实现。
- 不引入临时架构。
- 不提前实现后续阶段。
- 不为了当前任务修改 Architecture Contract。
- 每个任务必须有测试。
- 每一步完成后系统必须保持可运行。

## 目标与范围

接入第一个外部 MCP，推荐 GitHub。用途：查 repo、issue、源码和参考实现。内部 LearningEngine/SQLite/Workspace/compile/test 不做
MCP 化。

**DoD：**Project Mode 中 Tutor 能判断何时需要外部信息并通过 MCP 取回结果继续教学。
