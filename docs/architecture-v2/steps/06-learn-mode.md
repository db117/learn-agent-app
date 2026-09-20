# Step 6 — Learn Mode

## 执行原则

- 不兼容旧实现。
- 不引入临时架构。
- 不提前实现后续阶段。
- 不为了当前任务修改 Architecture Contract。
- 每个任务必须有测试。
- 每一步完成后系统必须保持可运行。

## 目标与范围

在真实 Practice 基础上重建课程系统。规划确认时只物化 LearnUnit 的 code、title、objective 大纲；进入当前 LearnUnit
后，应用层调用模型生成并保存 Concept、Example 和 Practice 内容快照。LearnUnit 关联这些内容和 PracticeTask；PracticeTask
可以是编码题或选择题。

## 完整学习闭环

学习 Runtime 读取 Bootstrap 已确认的 LearningJourney，进入当前 LearnUnit 时先生成或复用内容快照，再复用当前
`LearningPathItem` 加载 current LearnUnit。路径按章节顺序展开；进入一个空的 LEARNING Session 时，应用自动
发送当前单元的开始提示。Session 可以组织 Explain、Example、Practice 的交互，但不拥有学习进度。

```text
Explain → Example → Practice → 通过的 PracticeEvidence
       → Learning Domain 完成当前项并只推进下一个 PENDING LearnUnit
       → 没有下一个项时 LearningJourney COMPLETED
```

Learning Domain 是 mastery、completion 的唯一权威来源；PracticeEvidence 必须由应用层根据
ExecutionEnvironment 的客观验证结果回写。Tutor、UI 和 AgentScope Session 不得直接修改这些字段。

推进后创建或复用新的 `LEARNING Tutor Session`，重新加载新的当前 LearnUnit 和上下文，并自动发送该单元的
开始提示。未完成的后续项保持锁定；若当前项已完成且不存在下一个 `PENDING` 项，LearningJourney 进入
`COMPLETED`，Journey 自身仍只管理 `ACTIVE/ARCHIVED` 生命周期。

**DoD：**至少一个 TypeScript LearnUnit 可以完成 Explain → Example → Practice → PracticeEvidence 的整条链路；编码题和选择题都必须通过同一条
Learning Domain 进度链路，
PracticeEvidence 通过后，能继续加载下一个 LearnUnit，或在路径末尾恢复为 LearningJourney `COMPLETED`；
全程不让 Agent State 取代 Domain State。
