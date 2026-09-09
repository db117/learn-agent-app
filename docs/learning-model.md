# Learning Model

Learning Core 的主对象位于 `com.example.agent.learning`。教学知识使用 `LearnUnit`；
框架 `Skill` 只表示 Agent capability，不属于学习领域。

`LearningJourney` 保存语言、目标和生命周期；`LearnerProfile` 保存主要语言、经验和学习
目标。每个 Journey 的 `LearningPathItem` 同时保存 LearnUnit 关系、当前状态、mastery、
最佳分数、尝试次数以及诊断/学习通过原因。

课程目录由 `LearningLanguage` 和有序的 `LearnUnit` 组成。LearnUnit 包含前置关系、学习
目标、lesson 内容和通过策略参数。Question 是不可变定义，只包含 `MULTIPLE_CHOICE` 或
`CODING` 两种类型。

状态边界：

- Journey：`ACTIVE → COMPLETED`，也可以 `ARCHIVED`。
- Path item：`PENDING → CURRENT → COMPLETED` 或 `SKIPPED`。
- Attempt 完成后只追加新的 Retry，旧 Attempt 不更新。

运行时读取来自 SQLite。应用启动只创建表，不加载固定课程；用户提出目标语言创建新
Journey 时，由 LLM 为该 Journey 独立生成 LearnUnit，并通过
`learning_journey_learn_unit` 建立关联。同一 Journey 后续只读取已保存关联；不同 Journey
即使目标语言相同也不共享课程。诊断或 LearnUnit 评估需要题目时再由 LLM 生成或选择；模型
不可用、响应非法或题型覆盖不足时直接报错，不回退到已有题库，也不保存部分生成结果。已
持久化的目录和题目定义不会被模型响应覆盖，题目退役通过 `question_retirement` 保留历史
引用。
