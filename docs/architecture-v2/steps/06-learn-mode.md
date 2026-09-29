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
后，`learning-content-generation` Skill 生成并通过受限工具保存 Concept、Example 和 Practice 内容快照。LearnUnit
关联这些内容和仅用于编码练习的 PracticeTask。

## 完整学习闭环

学习 Runtime 读取 Bootstrap 已确认的 LearningJourney，进入当前 LearnUnit 时由 Skill 生成或复用内容快照，再由工具保存并加载
current LearnUnit。路径按章节顺序展开；进入一个空的 LEARNING Session 时，应用自动发送当前单元的开始提示。Session 可以组织
Explain、Example、Practice 的交互，但不拥有学习进度。

```text
Explain → Example → Practice → 编译/测试结果形成 PracticeEvidence
       → Tutor 判断 READY 或 CONTINUE
       → 学习者确认 READY 后，Learning Domain 完成当前项并只推进下一个 PENDING LearnUnit
       → 没有下一个项时 LearningJourney COMPLETED
```

Learning Domain 是 mastery、completion 的唯一权威来源；PracticeEvidence 必须由应用层根据
ExecutionEnvironment 的客观验证结果回写。Tutor 的 READY/CONTINUE 判断是学习评估，不直接修改领域状态；学习者确认 READY 后，
应用才调用 Learning Domain 推进操作。UI 和 AgentScope Session 不得直接修改这些字段。

推进后创建或复用新的 `LEARNING Tutor Session`，重新加载新的当前 LearnUnit 和上下文，并自动发送该单元的
开始提示。未完成的后续项保持锁定；若当前项已完成且不存在下一个 `PENDING` 项，LearningJourney 进入
`COMPLETED`，Journey 自身仍只管理 `ACTIVE/ARCHIVED` 生命周期。

**DoD：**至少一个 TypeScript LearnUnit 可以完成 Explain → Example → 编码 Practice → PracticeEvidence → Tutor 评估 → 学习者确认
的整条链路；编译/测试证据通过后，由 Tutor 判断学习状态，学习者确认 READY 后能继续加载下一个 LearnUnit，或在路径末尾将
LearningJourney 置为 `COMPLETED`；
全程不让 Agent State 取代 Domain State。
