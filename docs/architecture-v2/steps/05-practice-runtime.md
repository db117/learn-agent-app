# Step 5 — Practice Runtime

## 执行原则

- 不兼容旧实现。
- 不引入临时架构。
- 不提前实现后续阶段。
- 不为了当前任务修改 Architecture Contract。
- 每个任务必须有测试。
- 每一步完成后系统必须保持可运行。

## 目标与范围

实现 read/write/list file、compile、run_tests、run_program、diagnostics；加入 ExecutionEnvironment 和
LocalExecutionEnvironment。

完整链路：用户写 TS → compile 失败 → Tutor 观察 diagnostic → Hint → 用户修复 → tests pass → 应用层
写入通过的 PracticeEvidence → Learning Domain 更新当前 LearnUnit 的完成判定。

PracticeEvidence 是 Domain State，不是 Tutor Session 的结果。代码执行必须通过 ExecutionEnvironment；Tutor
只能观察诊断并引导用户，不能直接写入 PracticeEvidence、score、mastery 或 completion。应用层在固定验证项
全部通过后回写 PracticeEvidence，Learning Domain 决定 `practicePassed`，并与 Assessment 结果共同决定当前
LearningPathItem 是否完成。

当前项的完成条件是：Assessment 得分达到 70，且存在通过的 PracticeEvidence。两项条件都满足后，Learning
Domain 自动将当前项标记为完成，并将下一个有序 `PENDING` 项设为当前项；不存在下一个项时，将
LearningJourney 置为 `COMPLETED`。Session 只重新加载新的当前 LearnUnit，不自行修改领域状态，也不自动发送
下一单元的首条消息。

**DoD：**至少一个错误 TypeScript Practice 可以完整跑通；修复后能产生通过的 PracticeEvidence；在 Assessment
达到 70 后，领域状态能推进到下一个 `PENDING` LearnUnit，或在没有下一个单元时进入 LearningJourney
`COMPLETED`。达到 M1。
