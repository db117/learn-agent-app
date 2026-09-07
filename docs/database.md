# SQLite 持久化

默认数据库路径为：

```text
${user.home}/.desktop-learning-agent/agent.db
```

`AGENT_DATA_DIR` 修改目录，`APP_DATABASE` 修改完整数据库路径。
`DatabaseConfiguration` 创建目录并使用 Xerial SQLite JDBC 驱动，`schema.sql` 由
Spring Boot 初始化。

| 表 | 用途 |
| --- | --- |
| `session` | 持久化会话标识、用户、标题和时间戳 |
| `message` | 用户消息和最终 Assistant 消息 |
| `agent_run` | 运行状态、错误、开始时间和完成时间 |
| `event` | 有序的 ADK 事件 JSON，以及规范化的工具调用/结果字段 |
| `setting` | 小型本地键值设置；健康检查使用 `health.lastChecked` |

所有持久化都通过 `SqliteRepository` 和 Spring `JdbcClient` 完成。项目不使用 JPA
或第二个数据库。ADK 第一阶段的运行中会话状态保留在内存中，事件、消息和运行记录
构成持久化的应用链路记录。

## Learning Journey 表

| 表 | 用途 |
| --- | --- |
| `learning_language` / `learning_skill` | LLM 生成并持久化的语言元数据和技能目录 |
| `learning_journey_skill` | Journey 与专属技能的关联；隔离同语言的不同课程 |
| `question` | 唯一的运行时题库；定义 insert-only |
| `question_retirement` | Question 的 soft delete 标记；活动题库查询会排除它 |
| `learning_journey` / `learner_profile` | 学习目标和学习者背景 |
| `learner_skill` / `learning_path_item` | 掌握度、技能状态和路径历史 |
| `assessment` / `assessment_question` | 诊断或技能评估及固定题集 |
| `assessment_attempt` / `question_attempt` | 可重试的评估记录和答案/反馈 |
| `tutor_session` | Journey + LearningSkill 到现有 ADK session 的唯一关联 |

应用启动只创建表，不初始化 `learning_language`、`learning_skill` 或 `question`。用户提交
目标语言创建新 Journey 时，LLM 为该 Journey 独立生成技能和 Lesson 内容，经 Java 校验后
insert-only 写入，并在 `learning_journey_skill` 中建立关联；同一 Journey 后续直接读取关联。
不同 Journey 即使目标语言相同也不会共享技能。诊断或技能评估需要题目时再生成并写入。课程
目录和 Question 的已持久化定义都不被后续模型响应覆盖。Question 的题干、选项、正确答案、
分值和 rubric 不做更新。删除使用 `question_retirement`，历史 Assessment 仍可通过原 Question 读取。
