# Step 4 — Language Pack + Workspace

## 执行原则

- 不兼容旧实现。
- 不引入临时架构。
- 不提前实现后续阶段。
- 不为了当前任务修改 Architecture Contract。
- 每个任务必须有测试。
- 每一步完成后系统必须保持可运行。

## 目标与范围

实现 LanguagePack SPI、TypeScriptLanguagePack、LearningWorkspace、ProjectWorkspace、WorkspaceManager，并在前端引入 Monaco 与
File Tree。

**DoD：**在 Bootstrap 已存在且用户确认了需要语言 Workspace 的 LearningJourney 后自动初始化 Workspace，Monaco
可读写文件，后端能读取同一文件。
