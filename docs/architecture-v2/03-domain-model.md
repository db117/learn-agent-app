# Domain Model

## Learning Domain

核心模型：

```text
Learner
Journey
LearningJourney
Chapter
LearnUnit
LearningPathItem
Mastery
```

`Learner` 保存当前用户确认的背景能力描述；`Journey` 只保存用户要学习或完成的目标原文。

二者与生成路径的关系是：

```text
Learner 1 ── * Journey 1 ── 0..1 LearningJourney ── * LearnUnit
```

一个 Journey 最终只能有一份 LearningJourney。规划过程中的路径是 Agent State 草稿，用户确认后才进入 Domain State；Tutor 不得直接
写入 Journey、LearnUnit、mastery 或 completion。

`LearnUnit` 回答： **学什么？** 规划确认时只保存 code、title、objective；进入当前单元后由 Skill 生成、由工具保存内容快照。

## Practice Domain

```text
PracticeTask
PracticeAttempt
PracticeEvidence
```

`PracticeTask` 回答： **用什么任务练？**

建议字段：

```text
id
journeyId
learnUnitId
languagePackId
type
title
description
difficulty
starterTemplate
verificationPolicy
status
createdAt
```

`PracticeEvidence` 记录客观证据：

```text
compilePassed
testsPassed
testCount
lintPassed
runtimeResult
submittedFiles
verifiedAt
```

PracticeTask 只用于编码练习。PracticeEvidence 保存编译、测试等客观结果，供 Tutor 诊断和评估学习情况；客观检查通过本身不代表
LearnUnit 已掌握，也不自动推进路径。Tutor 评估学习者已准备好继续后，由学习者确认，Learning Domain 再更新学习进度。

## Mastery

Mastery 由 Learning Domain 管理。Tutor 的学习评估与客观 PracticeEvidence 是决策依据，但 Tutor 不直接写 mastery 或
completion。

Agent 可以提供分析，但不能直接写入 mastery 状态。

## 三类概念必须永久区分

```text
LearnUnit    = 学什么
PracticeTask = 用什么任务练
Skill        = Agent 遇到某类情况时怎么工作
```
