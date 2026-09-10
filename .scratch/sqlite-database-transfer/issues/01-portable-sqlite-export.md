# 01: 可移植 SQLite 快照导出

**What to build:** 学习者可以从桌面应用导出一个完整、一致、可直接传输的 SQLite `.db` 快照，作为本地备份或由学习者自行放入
OneDrive、Google Drive 同步目录；快照包含当前数据库的全部学习数据、Tutor 数据、AgentState 和设置。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] Spring Boot WebFlux Application 提供数据库导出 HTTP 接口，成功响应是标准 SQLite 二进制文件，并使用包含
  `learning-agent-java` 和 UTC 时间戳的下载文件名。
- [x] 导出使用 SQLite/Xerial 的一致性备份能力，不直接复制正在写入的活动数据库，也不要求复制 WAL 或 SHM sidecar。
- [x] 导出的数据库包含
  Journey、LearnUnit、LearningPathItem、Question、Assessment、Attempt、TutorSession、消息、TutorEvent、AgentState 和 setting
  的代表性数据。
- [x] 导出快照与活动数据库独立；导出完成后的本地写入不会改变已导出的文件。
- [x] SQLite schema 使用框架无关的产品级版本标识；快照内部包含 schema 版本、创建时间和来源应用标识。
- [x] 应用启动验证和快照验证使用同一套 schema、必需表和版本规则，不产生两个相互漂移的兼容性定义。
- [x] React 提供可访问的“导出数据库”入口，并展示导出中、成功和失败状态。
- [x] 导出中的 JDBC 和文件操作不占用 WebFlux event loop。
- [x] 自动化测试通过 WebFlux HTTP 和真实临时 SQLite 验证下载文件、完整性、全量数据和快照独立性。
- [x] 不引入云盘 SDK、账号登录、ZIP 或自定义归档格式。
