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
Assessment
Question
AssessmentAttempt
Answer
Mastery
```

`Learner` 保存当前用户确认的背景能力描述；`Journey` 只保存用户要学习或完成的目标原文。

二者与生成路径的关系是：

```text
Learner 1 ── * Journey 1 ── 0..1 LearningJourney ── * LearnUnit
```

一个 Journey 最终只能有一份 LearningJourney。规划过程中的路径是 Agent State 草稿，用户确认后才进入 Domain State；Tutor 不得直接
写入 Journey、LearnUnit、score、mastery 或 completion。

`LearnUnit` 回答： **学什么？**

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

## Project Domain

```text
Project
ProjectMilestone
ProjectEvidence
```

注意：`ProjectMilestone` 属于 Domain；`Agent Plan` 属于 Runtime，二者不等价。

## Mastery

Mastery 必须通过确定性策略计算，可以使用：AssessmentResult、PracticeEvidence、RepeatedPerformance、ReviewDebt。

Agent 可以提供分析，但不能直接写入 mastery 数值。

## 三类概念必须永久区分

```text
LearnUnit    = 学什么
PracticeTask = 用什么任务练
Skill        = Agent 遇到某类情况时怎么工作
```
