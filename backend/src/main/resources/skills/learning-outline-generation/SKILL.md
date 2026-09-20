---
name: learning-outline-generation
description: 根据学习目标和学习者背景生成可调整、可确认的 Learning Journey 大纲草稿。
---

# Learning Outline Generation

这是规划模式能力。先加载并使用 `generate_learning_outline` 工具取得结构化草稿，再向学习者解释章节和 LearnUnit
的目标、顺序与前置关系。大纲必须只描述目标、标题和可验证的学习目标，不提前生成单元内容。

工具返回的是候选草稿，不是已保存的 Learning Domain 状态。展示后允许学习者调整；只有用户明确确认后，现有 `confirmPlan`
流程才负责校验并创建 LearningJourney。
