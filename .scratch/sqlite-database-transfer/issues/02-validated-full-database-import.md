# 02: 经校验的 SQLite 整库导入

**What to build:** 学习者可以选择一个由应用导出的同版本 SQLite 快照，应用验证文件后自动备份当前数据库、用快照整体覆盖本地数据库，并重新加载导入后的完整学习状态。

**Blocked by:** 01: 可移植 SQLite 快照导出

**Status:** resolved

- [x] Spring Boot WebFlux Application 提供数据库导入 HTTP 接口，上传内容流式写入唯一临时文件，不把完整数据库一次性加载到内存。
- [x] 导入前验证 SQLite 文件头、完整性、外键关系、必需表和精确 schema 版本；非 SQLite、损坏、缺表或版本不匹配的文件被拒绝。
- [x] 所有验证失败都发生在当前数据库之外，失败后当前数据库内容保持不变。
- [x] 有效导入在覆盖前创建一个带时间戳、可独立打开且完整性检查通过的当前数据库备份。
- [x] 导入按整个数据库覆盖，不合并 Journey 或表；快照中的数据完整恢复，目标数据库独有的数据全部消失。
- [x] setting 和 AgentState 与其他 SQLite 数据一起导入，不进行字段级过滤或特殊同步。
- [x] 整库替换发生在没有活动写入的维护窗口中，并使用同文件系统原子操作替换数据库文件。
- [x] 替换失败时继续使用或恢复导入前数据库，并保留预导入备份。
- [x] 导入成功后删除上传临时文件、保留预导入备份，并返回 schema 版本、导入时间和是否需要重启。
- [x] React 提供本地 `.db` 文件选择入口，展示导入中、成功和验证失败状态，并在成功后清空旧页面状态、重新加载 Journey。
- [x] JDBC、SQLite backup/restore 和文件 I/O 不占用 WebFlux event loop。
- [x] 自动化测试通过 WebFlux HTTP 和真实临时 SQLite 验证全量恢复、目标独有数据删除、预备份和失败不修改原库。

## Answer

实现 `POST /api/database/import`：上传内容在 bounded-elastic 工作线程流式落到数据库目录唯一临时文件，独立执行 SQLite
header、完整性、外键、必需表和产品 schema 校验；通过 Xerial backup 创建 `backups/` 下的预导入快照后，使用同文件系统
`ATOMIC_MOVE` 整库替换，并在失败时从预备份恢复。成功响应返回 schema 版本、导入时间和 `restartRequired`；React 在 Tauri 中复用现有
JVM stop/start，再清空旧状态并重新加载 Journey。

验证：`./mvnw test -q` 通过（77 tests），`pnpm check` 通过；`DatabaseExportServiceTest` 覆盖 WebFlux
导入、整库恢复、目标独有数据删除、setting/AgentState、独立预备份、临时文件清理和无效上传不修改原库。
