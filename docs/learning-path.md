# Learning Path

Journey 生成后，`DeterministicLearningPathPlanner` 按前置关系拓扑排序，并以
`sequence/code` 作为稳定的并列排序键。所有状态和掌握数据都保存在 `LearningPathItem`；已
通过的 LearnUnit 成为 `COMPLETED`，跳过的 LearnUnit 成为 `SKIPPED`，剩余队列只由第一个
`PENDING` 项成为 `CURRENT`。

ProgressService 负责状态联动：

- 诊断通过的 LearnUnit 直接标记 Path `COMPLETED`，未通过的 LearnUnit 保持可学习状态。
- LearnUnit 评估通过后完成当前 Path item 并移动到下一个 pending LearnUnit。
- LearnUnit 评估失败保留 `CURRENT`；Skip 只写 `SKIPPED` 历史，不写 100 分。
- 全部 Path item 关闭后 Journey 变为 `COMPLETED`。

`masteryScore` 和 `bestAssessmentScore` 使用历史最大值，不能因一次较低的 retry
倒退。Path 展示完整历史，待学习队列只使用未关闭项目。

SQLite 对每个 Journey 建立唯一的 `CURRENT` 部分索引，因此重启或重新生成 Path 后仍只有一个当前 LearnUnit。
