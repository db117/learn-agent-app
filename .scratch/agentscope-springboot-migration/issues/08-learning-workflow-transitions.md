# 08: Learning Workflow：Retry、Skip、Next、Completed

**What to build:** Assessment 结果可以驱动确定性的学习状态迁移，学习者能 Retry、Skip、进入下一个 LearnUnit，并在最后一个单元通过后完成
Journey。

**Blocked by:** 07: Assessment 评分与 Attempt 历史

**Status:** resolved

- [x] Learning Engine 根据 Assessment 结果确定性地执行 Passed、Retry Required、Skip、Next 和 Completed transition。
- [x] Retry 回到同一个 LearnUnit，并继续使用固定 Question 集合；旧 Attempt 和旧分数保持可读。
- [x] Skip 将当前 `LearningPathItem` 标记为 SKIPPED，不设置虚假的满分，也不改写为 PASSED。
- [x] Passed 与 Skipped 在 API、SQLite 和 React 展示中永久区分。
- [x] 最后一个有效 LearnUnit 通过后 Journey 进入 COMPLETED；非最后一个单元进入下一个可学习项。
- [x] 每次 transition 在事务中同时保持学习状态、路径状态和 transition 记录的一致性；应用重启后可以继续下一步。
- [x] React 提供 Continue、Retry、Skip 和 Next 操作，并根据服务端状态禁用非法操作；自动化测试覆盖完整状态矩阵。

## Answer

- `ProgressService` 统一执行 Assessment Passed/Retry、Skip、Next 和 Journey Completed；Path transition 使用一致的 Path 状态，Journey 完成单独记录 Journey 状态。
- Retry 通过原 Assessment 创建新 Attempt，固定题集和历史 Attempt 不变；Skip 清除通过来源和通过时间，只保留 `SKIPPED` 状态及已有非虚构历史分数。
- WebFlux API 增加 Continue、Retry、Skip 和 Next 入口；React 根据服务端 Journey、Path 和 Attempt 状态禁用非法操作，并明确展示 Passed 与 Skipped。
- 验证：Progress/Assessment 自动化测试覆盖通过、重试、跳过、最后一项完成、Next 幂等和非法状态；完整 `pnpm check` 通过。
