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
LearningPathItem，再把新 LearningJourney 的 ID 挂回 Journey。进入当前 LearnUnit 后，`learning-content-generation` Skill
生成 Concept、Example、Practice，再由数据库工具将内容快照写回对应 LearnUnit。后续 Bootstrap 通过该 ID 初始化
Workspace，并创建/恢复 LEARNING Tutor Session；Session 只读取当前 LearnUnit。

PracticeAttempt 和 PracticeEvidence 是不可变历史记录；重试产生新记录。编译和测试结果以 PracticeEvidence 保存，交给 Tutor
诊断和评估。Tutor 判断学习者已准备好继续并由学习者确认后，Learning Domain 才更新当前项的 completion/mastery 并推进路径；
客观检查通过本身不自动完成 LearnUnit。

当前单用户流程不引入并发控制或通用跨聚合事务。确认规划的写入顺序是“保存 LearningJourney，再挂回 Journey”；
若挂接失败，应用层清理本次新建的 LearningJourney，Journey 保持未挂接状态并允许重试。这里的清理是本流程的
失败补偿，不构成通用事务框架。

文件系统：源码、Workspace 文件、Artifact 和大日志。

完整 stdout/stderr 不默认长期塞 SQLite，只保存摘要、exit code、duration 等。

## 当前数据库定向迁移

针对已有 SQLite schema v13 做一次性 v13 → v14 定向迁移，不提供通用旧版本兼容，也不是 v1 compatibility。迁移开始前备份完整数据库；
只接受识别为 schema v13 的数据库，在单个事务中执行，全部成功后写入 schema v14，失败时回滚并保留备份。

迁移只删除已移除能力的数据和结构：

- 按外键依赖顺序删除并移除 `project_evidence`、`project_milestone`、`project`。
- 删除 `type = 'CHOICE'` 的 PracticeTask 及其关联 PracticeAttempt、PracticeEvidence；保留编码 PracticeTask 及其历史。
- 从 `practice_task` 和 `practice_evidence` 移除选择题专属的 `choice_question`、`choice_correct` 字段。
- 清理保留的 `verification_policy` JSON 中选择题专属的 `requireChoice` 配置。
- 保留非选择题的学习记录；LearningPathItem 的完成状态原样保留，包括曾由选择题证据支持的状态，不因证据删除而重算。
- 数据库迁移执行前，由主代理核实并登记与待删除 Project 记录对应的 app-managed
  `~/.learn-agent/projects/{projectId}/workspace` 和 `~/.learn-agent/projects/{projectId}/artifacts` 目录及 Project
  ID；数据库迁移成功后再移除这些目录，不扫描或删除其他文件目录。
- 保留其他 Learner、Journey、LearningJourney、LearnUnit、LearningPathItem、Mastery、编码练习、配置和文件数据。

应用不得将这次迁移扩展为任意旧 schema 修复；schema 版本不是 v13 时应停止并报告不支持。
