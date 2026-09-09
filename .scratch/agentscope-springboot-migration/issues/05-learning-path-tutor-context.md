# 05: LearningPathItem、TutorContext 与进度展示

**What to build:** 生成 Journey 后，系统根据 LearnUnit 建立确定性的 Learning Path，React 展示当前和下一
LearnUnit，TutorAgent 获得只读 TutorContext。

**Blocked by:** 04: Journey/LearnUnit 的 LLM 生成

**Status:** resolved

- [x] Learning Engine 使用 `LearningPathItem` 表示 Journey 与 LearnUnit 的学习关系，不引入 `LearnerLearnUnit`。
- [x] Learning Path 的顺序、前置关系、当前项和下一项由确定性 Java 逻辑决定，不由 TutorAgent 直接决定。
- [x] Learning Path 和当前进度写入 SQLite，重启后仍能恢复唯一的当前 LearnUnit。
- [x] 每次 TutorAgent 调用获得只读 TutorContext，至少包含目标语言、学习者背景、当前 LearnUnit、已掌握摘要、薄弱点和下一步。
- [x] TutorContext 不允许直接修改分数、通过状态、Skip 状态或 Learning Path。
- [x] React 展示当前 LearnUnit、下一步、进度、掌握度和状态；自动化测试覆盖当前项唯一性、路径恢复和上下文边界。

## Answer

- 将掌握度、最佳成绩、尝试次数、通过来源和时间字段并入 `learning_path_item`，删除 `LearnerLearnUnit` 及旧 Journey 当前指针；SQLite 增加每个 Journey 唯一 `CURRENT` 部分索引。
- Journey 创建后由确定性 planner 生成并持久化 Path；ProgressService、Assessment 和 TutorContext 全部从 Path 读取或写入，Tutor middleware 每次调用注入不可变上下文。
- React 从 Path 恢复当前项并展示下一项、进度、掌握度和状态；新增 planner、Progress、TutorContext 和 SQLite 集成覆盖。
