# SQLite 持久化

默认数据库路径为 `${user.home}/.learning-agent-java/agent.db`。`AGENT_DATA_DIR`
修改目录，`APP_DATABASE` 修改完整数据库路径。`DatabaseConfiguration` 创建目录并使用
Xerial SQLite JDBC 驱动，`DatabaseInitializer` 只在空库执行 `schema.sql`。

正式桌面运行时数据库位于本机用户数据目录的 `.learning-agent-java/agent.db`，不应放入
OneDrive、Google Drive 或其他同步目录。导出的单文件快照可以手动保存到任意目录，再由用户
自行复制到另一台设备；应用没有云盘登录、API 接入或自动同步。

| 表                                        | 用途                                                |
|-------------------------------------------|-----------------------------------------------------|
| `session` / `message` / `agent_run`       | Tutor 会话、消息和运行摘要                          |
| `event`                                   | 有序的 TutorAgent 事件 JSON、工具调用和结果         |
| `setting`                                 | 本地键值设置和健康检查                              |
| `learning_language` / `learn_unit`        | LLM 生成的语言元数据和 Journey 课程单元             |
| `learning_journey_learn_unit`             | Journey 与专属 LearnUnit 的关联                     |
| `learning_journey` / `learner_profile`    | 学习目标和学习者背景                                |
| `learning_path_item`                      | Journey 与 LearnUnit 关系、掌握度、状态和路径历史   |
| `question` / `question_retirement`        | insert-only 题目和 soft delete 标记                 |
| `assessment` / `assessment_question`      | 评估定义和创建时固定的题集                          |
| `assessment_attempt` / `question_attempt` | 可重试的评估记录、答案和反馈                        |
| `tutor_session`                           | Journey + LearnUnit 到 Tutor session 的唯一关联     |
| `agent_state`                             | AgentScope Harness 的会话运行时状态，与学习事实分离 |
| `workflow_transition`                     | Java Learning Engine 的可审计状态迁移               |

所有持久化都通过 `SqliteRepository`、`LearningRepository` 和 Spring `JdbcClient` 完成，
不使用 JPA 或第二个数据库。应用启动只创建表，不初始化课程或 Question。用户提交目标
语言创建 Journey 时，LLM 为该 Journey 生成 LearnUnit，经 Java 校验后 insert-only 写入；
同一 Journey 后续从 SQLite 恢复，其他 Journey 不会看到这些 LearnUnit。

Question 的题干、答案、分值、rubric 和历史引用不会被更新。退役写入
`question_retirement`，历史 Assessment 仍可读取原题。

便携数据库协议由 `schema_metadata` 中的产品级 `schema.marker`、精确的 `schema.version = 1`、
`snapshot.created_at` 和 `source.application_id` 标识。`GET /api/database/export` 使用
Xerial SQLite backup API 生成独立的 `learning-agent-java-<UTC 时间>.db` 文件；不会复制活动库的
WAL/SHM 文件。已有 SQLite 文件如果缺少这些标识、必需表或版本不是当前版本，启动会明确报错，
不会删除、覆盖或迁移数据。顶部“导入数据库”可选择本地 `.db` 快照；后端会先在数据库目录写入临时文件，
校验 SQLite 完整性、外键、必需表和精确版本，再生成数据库目录下
`backups/learning-agent-java-pre-import-<UTC 时间>.db` 预导入备份并原子替换整库。
手动流程是：点击“导出数据库”保存 `.db` 快照，传输该快照，再点击“导入数据库”选择它；
导入成功后的预导入备份会一直保留，由用户自行管理，不会自动上传、删除或合并。
验证失败不会改变原库，成功后页面清空旧状态并重新加载 Journey。JDBC、文件 I/O 和 SQLite backup 操作必须在 WebFlux event loop
之外执行。

导入快照时间比当前数据库早超过一分钟时，首次请求返回 `409 database_import_stale`，并提供
`snapshotCreatedAt`、`currentDatabaseAt` 和 `confirmationRequired`；只有带
`?confirm=true` 的同一快照请求才会覆盖当前库。`database_agent_busy` 表示 TutorAgent 仍在运行，
`database_transfer_busy` 表示已有导入或导出，二者都应稍后重试。文件损坏、未知 schema、版本不兼容、
预备份失败和替换失败分别使用项目错误码，失败会清理上传临时文件并保留当前库；替换开始后失败会用
`backups/` 中的预导入备份恢复。传输期间 TutorAgent 不会被取消，成功替换后 EventHub 订阅会关闭。
非 Tauri 开发模式会明确提示手动重启 JVM，不会伪装成已恢复的页面状态。
