---
name: practice-test-generation
description: 为当前 LearnUnit 生成一道基于现有内容的四选一练习题；当前 Step 7 只支持选择题。
---

# Practice Test Generation

使用 `generate_practice_test` 工具调用现有 ChoiceQuestionGenerator。题目只能由当前 LearnUnit
的目标和内容支持，必须有四个有迷惑性但无歧义的选项。向学习者展示题干和选项，不泄露正确答案。

这项能力不替代固定的 code-task、Vitest 或 ExecutionEnvironment 流程。用户提交答案后，现有 Practice API 负责判定、记录
PracticeEvidence，并按 Learning Domain 规则推进进度。
