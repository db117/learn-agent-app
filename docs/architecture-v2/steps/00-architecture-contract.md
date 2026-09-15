# Step 0 — Architecture Contract

## 执行原则

- 不兼容旧实现。
- 不引入临时架构。
- 不提前实现后续阶段。
- 不为了当前任务修改 Architecture Contract。
- 每个任务必须有测试。
- 每一步完成后系统必须保持可运行。

## 目标与范围

彻底清场并建立 v2 唯一架构合同。

删除旧 backend/frontend 业务代码、旧 REST API、SQLite migration、DTO、AgentState、TutorSession、旧 prompt 和兼容层。保留 Git
历史、通用工具链与可复用静态资源。

创建 README、AGENTS、CONTEXT 与 architecture docs。

**DoD：**仓库不存在 Legacy/V1/Deprecated 兼容代码；架构规则明确；后续实现无需依赖旧业务代码。
