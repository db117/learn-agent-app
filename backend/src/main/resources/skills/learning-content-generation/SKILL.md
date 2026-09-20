---
name: learning-content-generation
description: 为当前 LearnUnit 生成 Concept、Example 和 Practice 内容，并通过现有应用服务保存内容快照。
---

# Learning Content Generation

只针对当前 LearnUnit，使用 `generate_learning_content` 工具调用现有 typed Generator/Application
Service。内容必须由当前单元标题和目标支持，包含 Concept、Example、Practice，不扩展到下一个单元。

工具可能把首次生成的内容快照写入 Learning Domain，但不会修改掌握度、完成状态或 PracticeEvidence。返回后用学习者的语言讲解内容；不要声称完成了
Practice。
