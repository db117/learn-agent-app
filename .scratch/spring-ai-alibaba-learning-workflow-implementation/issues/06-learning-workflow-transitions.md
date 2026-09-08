# 06: Learning Workflow 的 Pass/Retry/Skip/Next/Completed

**What to build:** 学习者提交评估或选择操作后，系统按确定性规则推进 Learning Path；Pass、Retry、Skip、Next 和 Journey 完成状态由 Java 持久化控制，并可在重启后继续。

**Blocked by:** 02: Journey 创建与 LLM LearnUnit 生成/恢复; 05: Coding 评估与受限 LLM 反馈

**Status:** complete

- [x] 每次用户 Action 独立执行一次 Graph，不让 Graph 长时间等待用户输入。
- [x] Score Engine 和 Pass Policy 唯一决定 Pass 或 Retry，覆盖 MC/Coding 权重与阈值规则。
- [x] Pass 推进到满足条件的下一个 LearnUnit，Retry 保持当前 LearnUnit。
- [x] Skip 与 Passed 状态明确区分，并记录对应 Workflow Transition。
- [x] 最后一个可学习 LearnUnit 完成后 Journey 进入 COMPLETED。
- [x] Journey、Path、Attempt、状态迁移和 Skip 结果保存到 SQLite，重启后可恢复。
- [x] Learning Dashboard 可以展示当前 LearnUnit、历史最高成绩、错误反馈和下一步操作。

**Evidence:** `LearningWorkflowGraph` runs one Graph per action; ProgressService transition and persistence tests pass.
