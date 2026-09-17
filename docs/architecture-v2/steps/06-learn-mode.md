# Step 6 — Learn Mode

## 执行原则

- 不兼容旧实现。
- 不引入临时架构。
- 不提前实现后续阶段。
- 不为了当前任务修改 Architecture Contract。
- 每个任务必须有测试。
- 每一步完成后系统必须保持可运行。

## 目标与范围

在真实 Practice 基础上重建课程系统。LearnUnit 关联 Concept、Example、PracticeTask、Assessment。

## 完整学习闭环

学习 Runtime 读取 Bootstrap 已确认的 LearningJourney，复用当前 `LEARNING Tutor Session`，并从当前
`LearningPathItem` 加载 current LearnUnit。用户首条消息开始 Tutor 回合；Session 可以组织 Explain、Example、
Practice 和 Assessment 的交互，但不拥有学习进度。

```text
Explain → Example → Practice → 通过的 PracticeEvidence
       → Independent Assessment → AssessmentAttempt 评估
       → Assessment score >= 70 且 PracticeEvidence 通过
       → Learning Domain 完成当前项并推进下一个 PENDING LearnUnit
       → 没有下一个项时 LearningJourney COMPLETED
```

Learning Domain 是 score、mastery、completion 和 assessment 的唯一权威来源。Assessment 必须由确定性策略
评估；PracticeEvidence 必须由应用层根据 ExecutionEnvironment 的客观验证结果回写。Tutor、UI 和 AgentScope
Session 不得直接修改这些字段。

推进后复用同一个 `LEARNING Tutor Session`，重新加载新的当前 LearnUnit 和上下文；不自动发送下一单元的首条
消息，等待用户继续输入。若当前项已完成且不存在下一个 `PENDING` 项，LearningJourney 进入 `COMPLETED`，
Journey 自身仍只管理 `ACTIVE/ARCHIVED` 生命周期。

**DoD：**至少一个 TypeScript LearnUnit 可以完成 Explain → Example → Practice → PracticeEvidence →
Assessment 的整条链路；Assessment 达到 70 且 PracticeEvidence 通过后，能继续加载下一个 LearnUnit，或在
路径末尾恢复为 LearningJourney `COMPLETED`；全程不让 Agent State 取代 Domain State。
