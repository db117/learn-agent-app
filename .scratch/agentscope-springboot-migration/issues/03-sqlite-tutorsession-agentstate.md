# 03: SQLite 新库、TutorSession 与 AgentState 恢复

**What to build:** 学习者可以创建并重入一个 Journey + LearnUnit 的 TutorSession；关闭并重新创建 Agent 后，AgentState 从新的
SQLite 数据库恢复，且阻塞持久化不会占用 WebFlux event loop。

**Blocked by:** 01: Spring Boot WebFlux TutorAgent 流主链路

**Status:** ready-for-agent

- [ ] 新应用使用 Xerial SQLite JDBC 和 Spring JDBC/JdbcClient 保存 TutorSession 与 AgentState。
- [ ] JDBC、AgentState 和事务操作通过明确的工作线程边界执行，不直接占用 WebFlux event loop。
- [ ] 相同 Journey + LearnUnit 重入时复用同一 TutorSession；切换 LearnUnit 时不复用前一个 Agent context。
- [ ] 销毁 Agent 实例后重新创建 Agent，并使用相同 session 身份，可以恢复之前的 AgentState。
- [ ] AgentState 恢复失败返回明确错误，不静默创建空会话、不重置上下文，也不修改 Learning Journey。
- [ ] 检测到旧 schema 或无法确认数据库属于新链路时明确报错，不静默删除、覆盖或迁移旧数据。
- [ ] 自动化测试覆盖重启恢复、session 隔离、阻塞隔离、学习状态与 AgentState 分离。
