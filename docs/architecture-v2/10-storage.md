# Storage

## SQLite 保存业务事实

建议核心表：

```text
learner
learning_journey
chapter
learn_unit
learning_path_item
assessment
question
assessment_attempt
answer
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

数据库：metadata、状态、关系、结构化 evidence、时间戳。

文件系统：源码、Workspace 文件、Artifact、大日志、生成项目。

完整 stdout/stderr 不默认长期塞 SQLite，只保存摘要、exit code、duration 等。

## Clean-slate

v2 不提供旧数据库迁移，不做 dual-write、fallback query 或 legacy schema compatibility。
