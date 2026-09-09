# 05: LearningPathItem、TutorContext 与进度展示

**What to build:** 生成 Journey 后，系统根据 LearnUnit 建立确定性的 Learning Path，React 展示当前和下一
LearnUnit，TutorAgent 获得只读 TutorContext。

**Blocked by:** 04: Journey/LearnUnit 的 LLM 生成

**Status:** ready-for-agent

- [ ] Learning Engine 使用 `LearningPathItem` 表示 Journey 与 LearnUnit 的学习关系，不引入 `LearnerLearnUnit`。
- [ ] Learning Path 的顺序、前置关系、当前项和下一项由确定性 Java 逻辑决定，不由 TutorAgent 直接决定。
- [ ] Learning Path 和当前进度写入 SQLite，重启后仍能恢复唯一的当前 LearnUnit。
- [ ] 每次 TutorAgent 调用获得只读 TutorContext，至少包含目标语言、学习者背景、当前 LearnUnit、已掌握摘要、薄弱点和下一步。
- [ ] TutorContext 不允许直接修改分数、通过状态、Skip 状态或 Learning Path。
- [ ] React 展示当前 LearnUnit、下一步、进度、掌握度和状态；自动化测试覆盖当前项唯一性、路径恢复和上下文边界。
