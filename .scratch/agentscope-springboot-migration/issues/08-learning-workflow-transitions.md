# 08: Learning Workflow：Retry、Skip、Next、Completed

**What to build:** Assessment 结果可以驱动确定性的学习状态迁移，学习者能 Retry、Skip、进入下一个 LearnUnit，并在最后一个单元通过后完成
Journey。

**Blocked by:** 07: Assessment 评分与 Attempt 历史

**Status:** ready-for-agent

- [ ] Learning Engine 根据 Assessment 结果确定性地执行 Passed、Retry Required、Skip、Next 和 Completed transition。
- [ ] Retry 回到同一个 LearnUnit，并继续使用固定 Question 集合；旧 Attempt 和旧分数保持可读。
- [ ] Skip 将当前 `LearningPathItem` 标记为 SKIPPED，不设置虚假的满分，也不改写为 PASSED。
- [ ] Passed 与 Skipped 在 API、SQLite 和 React 展示中永久区分。
- [ ] 最后一个有效 LearnUnit 通过后 Journey 进入 COMPLETED；非最后一个单元进入下一个可学习项。
- [ ] 每次 transition 在事务中同时保持学习状态、路径状态和 transition 记录的一致性；应用重启后可以继续下一步。
- [ ] React 提供 Continue、Retry、Skip 和 Next 操作，并根据服务端状态禁用非法操作；自动化测试覆盖完整状态矩阵。
