# Learning Path

诊断提交后，`DeterministicLearningPathPlanner` 按前置关系拓扑排序，并以
`sequence/code` 作为稳定的并列排序键。已通过的 LearnUnit 成为 `COMPLETED`，跳过的 LearnUnit
成为 `SKIPPED`，剩余队列只由第一个 `PENDING` 项成为 `CURRENT`。

ProgressService 负责状态联动：

- 诊断通过的 LearnUnit 直接标记 `PASSED`，未通过的 LearnUnit 进入 `READY`。
- LearnUnit 评估通过后完成当前 Path item 并移动到下一个 pending LearnUnit。
- LearnUnit 评估失败保留 `LEARNING`；Skip 只写 `SKIPPED` 历史，不写 100 分。
- 全部 Path item 关闭后 Journey 变为 `COMPLETED`。

`masteryScore` 和 `bestAssessmentScore` 使用历史最大值，不能因一次较低的 retry
倒退。Path 展示完整历史，待学习队列只使用未关闭项目。
