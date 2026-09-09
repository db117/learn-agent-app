# Issue tracker：本地 Markdown

本仓库的需求、规格和任务记录在 `.scratch/` 下的 Markdown 文件中。

## 约定

- 每个 feature 使用一个目录：`.scratch/<feature-slug>/`
- 规格文件为 `.scratch/<feature-slug>/spec.md`
- 实现任务每个使用一个文件：`.scratch/<feature-slug>/issues/<NN>-<slug>.md`，从 `01` 开始编号，不使用合并的任务文件
- 分诊状态记录在任务文件顶部附近的 `Status:` 行中，具体角色字符串见 `triage-labels.md`
- 评论和对话历史追加在文件底部的 `## Comments` 标题下

## 技能要求“发布到 issue tracker”时

在 `.scratch/<feature-slug>/` 下创建文件；目录不存在时一并创建。

## 技能要求“获取相关任务”时

读取引用的文件。用户通常会直接提供文件路径或任务编号。

## Wayfinding 操作

`/wayfinder` 使用一张地图文件和每个任务对应的子文件：

- **地图**：`.scratch/<effort>/map.md`，记录 Notes、已做决策和待解决问题
- **子任务**：`.scratch/<effort>/issues/NN-<slug>.md`，从 `01` 开始编号；正文中的 `Type:` 记录任务类型（`research` /
  `prototype` / `grilling` / `task`），`Status:` 记录 `claimed` / `resolved`
- **阻塞关系**：使用顶部附近的 `Blocked by: NN, NN`；列出的文件全部为 `resolved` 后，任务才算解除阻塞
- **前沿任务**：扫描 `.scratch/<effort>/issues/`，按编号优先选择未完成、未阻塞且未认领的任务
- **认领**：设置 `Status: claimed` 后再开始工作
- **解决**：在 `## Answer` 下追加答案，设置 `Status: resolved`，再将简要结论和链接追加到地图的 Decisions-so-far 中
