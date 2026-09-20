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

Step 4 先拆为四个窄任务：核心 LanguagePack/TypeScript pack、Workspace/Manager、Bootstrap/File API、Monaco/File Tree/Vitest。
本阶段只实现 LanguagePack 的核心声明能力；Practice 行为和代码执行留给后续阶段。

当前已确认的 LearningJourney 以 `learningJourneyId != null` 表示；Bootstrap 只为当前 Journey 幂等初始化 Learning
Workspace，不补做路径确认编排。

**DoD：**在 Bootstrap 已存在且用户确认了需要语言 Workspace 的 LearningJourney 后自动初始化 Workspace，Monaco
可读写文件，后端能读取同一文件。
