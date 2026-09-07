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
