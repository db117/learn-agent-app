# Learning Model

Phase 2 的主对象位于 `com.example.agent.learning`，名称使用 `LearningSkill`，
避免与泛化的 Skill 混淆。

`LearningJourney` 保存语言、目标、生命周期和当前技能；`LearnerProfile` 保存主要
语言、经验和学习目标。每个 Journey 的 `LearnerSkill` 保存状态、mastery、最佳分数、
尝试次数以及诊断/学习通过原因。

课程目录由 `LearningLanguage` 和有序的 `LearningSkill` 组成。Skill 包含前置技能、
学习目标、lesson 内容和通过策略参数。Question 是不可变定义，只包含
`MULTIPLE_CHOICE` 或 `CODING` 两种类型。

状态边界：

- Journey：`ACTIVE → COMPLETED`，也可以 `ARCHIVED`。
- LearnerSkill：`LOCKED → READY → LEARNING/ASSESSING → PASSED`；`SKIPPED` 是历史
  结果，不等于掌握。
- Path item：`PENDING → CURRENT → COMPLETED` 或 `SKIPPED`。
- Attempt 完成后只追加新的 retry，旧 Attempt 不更新。

运行时读取来自 SQLite。应用启动只创建表，不加载固定课程资源；用户提出目标语言创建新
Journey 时，由 LLM 为该 Journey 独立生成技能和 Lesson 内容，并通过
`learning_journey_skill` 建立技能关联。同一 Journey 后续只读取已保存关联；不同 Journey
即使目标语言相同也不共享课程。诊断或技能评估需要题目时再由 LLM 生成或选择。已持久化的
目录和题目定义不会被模型响应覆盖，题目退役通过 `question_retirement` 保留历史引用。
