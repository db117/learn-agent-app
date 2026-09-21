---
name: learning-outline-generation
description: 在规划模式根据学习目标和学习者背景生成可调整、可确认的 Learning Journey 大纲草稿。
---

# Learning Outline Generation

这是规划模式能力。直接根据 TutorContext 中的 Journey 目标和 Learner 背景生成规划草稿，不调用模型生成工具。

只输出一个 JSON 对象，不要 Markdown、代码围栏、解释或额外文字：

```json
{"chapters":[{"code":"basics","title":"章节标题","units":[{"code":"intro","title":"单元标题","objective":"可验证目标"}]}]}
```

根据目标范围、学习者背景和可验证性自主决定 chapters 与 units 的数量；不要固定数量或为了凑数拆分。
chapters 至少一个，每个 chapter 至少一个 unit；所有 code 必须是全局唯一的小写短横线编码，不能只用数字、章节序号或通用的
`unit-1` 这类重复编号；应使用包含主题前缀的 code，例如 `typescript-basics`、`typescript-types`、
`typescript-basics-variables`，确保不同 chapter 下的 unit 也不会重复。
只生成标题和目标，不生成 Concept、Example、Practice 内容。

这是候选草稿，不是已保存的 Learning Domain 状态。展示后允许学习者调整；只有用户明确确认后，现有 `confirmPlan`
流程才负责校验并创建 LearningJourney。不要声称路径已经保存。
