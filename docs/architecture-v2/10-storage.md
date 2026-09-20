# Storage

## SQLite 保存业务事实

建议核心表：

```text
learner
learning_journey
chapter
learn_unit
learning_path_item
mastery
practice_task
practice_attempt
practice_evidence
project
project_milestone
project_evidence
language_pack_config
```

## 默认不设计 Runtime 表

```text
agent_message
agent_memory
agent_plan
agent_tool_call
agent_session
```

这些优先由 Agent Runtime 管理。若以后做 Analytics/Trace，可建立只读 Projection。

## 文件与数据库边界

数据库：metadata、状态、关系、结构化 evidence、时间戳。LearningJourney、LearnUnit、LearningPathItem、
PracticeEvidence 和完成/掌握结果属于 Domain State；AgentScope 的 session、
memory、plan、消息和规划草稿属于 Agent State，不写入这些领域事实表。

本地单用户的目标引导至少保存：

```text
learner.background_summary
journey.goal_description
journey.status
journey.is_current
journey.learning_journey_id
```

Journey 可以先没有 LearningJourney，且此时 `learningJourneyId` 为空；规划草稿和 Tutor 对话继续由 AgentScope
Runtime 管理，确认后才写入路径事实。确认规划时，应用层按草稿的有序段落/阶段只保存大纲字段的多个 LearnUnit、
LearningPathItem，再把新 LearningJourney 的 ID 挂回 Journey。进入当前 LearnUnit 后，应用层将模型生成的
Concept、Example、Practice 内容快照写回对应 LearnUnit。后续 Bootstrap 通过该 ID 初始化
Workspace，并创建/恢复 LEARNING Tutor Session；Session 只读取当前 LearnUnit。

PracticeAttempt 和 PracticeEvidence 是不可变历史记录；重试产生新记录。Learning Domain 根据通过的
PracticeEvidence 更新当前项的 completion/mastery，并推进下一个 `PENDING` 项；没有下一个项时将 LearningJourney 置为
`COMPLETED`。

当前单用户流程不引入并发控制或通用跨聚合事务。确认规划的写入顺序是“保存 LearningJourney，再挂回 Journey”；
若挂接失败，应用层清理本次新建的 LearningJourney，Journey 保持未挂接状态并允许重试。这里的清理是本流程的
失败补偿，不构成通用事务框架。

文件系统：源码、Workspace 文件、Artifact、大日志、生成项目。

完整 stdout/stderr 不默认长期塞 SQLite，只保存摘要、exit code、duration 等。

## Clean-slate

v2 不提供旧数据库迁移，不做 dual-write、fallback query 或 legacy schema compatibility。
