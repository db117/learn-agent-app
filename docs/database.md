# SQLite 持久化

默认数据库路径为 `${user.home}/.desktop-learning-agent/agent.db`。`AGENT_DATA_DIR`
修改目录，`APP_DATABASE` 修改完整数据库路径。`DatabaseConfiguration` 创建目录并使用
Xerial SQLite JDBC 驱动，`DatabaseInitializer` 只在空库执行 `schema.sql`。

| 表 | 用途 |
| --- | --- |
| `session` / `message` / `agent_run` | Tutor 会话、消息和运行摘要 |
| `event` | 有序的 TutorAgent 事件 JSON、工具调用和结果 |
| `setting` | 本地键值设置和健康检查 |
| `learning_language` / `learn_unit` | LLM 生成的语言元数据和 Journey 课程单元 |
| `learning_journey_learn_unit` | Journey 与专属 LearnUnit 的关联 |
| `learning_journey` / `learner_profile` | 学习目标和学习者背景 |
| `learning_path_item` | Journey 与 LearnUnit 关系、掌握度、状态和路径历史 |
| `question` / `question_retirement` | insert-only 题目和 soft delete 标记 |
| `assessment` / `assessment_question` | 评估定义和创建时固定的题集 |
| `assessment_attempt` / `question_attempt` | 可重试的评估记录、答案和反馈 |
| `tutor_session` | Journey + LearnUnit 到 Tutor session 的唯一关联 |
| `agent_state` | AgentScope Harness 的会话运行时状态，与学习事实分离 |
| `workflow_transition` | Java Learning Engine 的可审计状态迁移 |

所有持久化都通过 `SqliteRepository`、`LearningRepository` 和 Spring `JdbcClient` 完成，
不使用 JPA 或第二个数据库。应用启动只创建表，不初始化课程或 Question。用户提交目标
语言创建 Journey 时，LLM 为该 Journey 生成 LearnUnit，经 Java 校验后 insert-only 写入；
同一 Journey 后续从 SQLite 恢复，其他 Journey 不会看到这些 LearnUnit。

Question 的题干、答案、分值、rubric 和历史引用不会被更新。退役写入
`question_retirement`，历史 Assessment 仍可读取原题。

新链路数据库由 `schema_metadata` 中的 `schema.version` 标识。已有 SQLite 文件如果缺少
该标识或缺少新链路表，或版本不是当前版本，启动会明确报错，不会删除、覆盖或迁移数据。JDBC 和 AgentState
操作必须在 WebFlux event loop 之外执行。
