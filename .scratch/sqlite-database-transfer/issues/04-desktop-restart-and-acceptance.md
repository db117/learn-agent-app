# 04: 桌面重启恢复与最终验收

**What to build:** 学习者可以在打包后的桌面链路中完成数据库导出、传输和导入；需要刷新运行时状态时，Tauri 只负责重启
JVM，React 随后从导入数据库恢复完整 Journey、TutorSession 和兼容 AgentState。

**Blocked by:** 03: 旧快照、忙碌与失败保护

**Status:** resolved

- [x] 导入结果要求重启时，React 使用既有桌面进程生命周期停止并重新启动受管理的 JVM 后端，然后重新加载应用数据。
- [x] Tauri 不读取、验证、查询、合并或修改 SQLite；所有数据库传输规则保留在 Java 应用边界。
- [x] 非 Tauri 开发运行方式在需要重启时返回明确提示，不伪装成已经完成恢复。
- [x] 桌面 smoke 使用隔离数据目录验证导出、整库导入、JVM 重启、固定端口恢复和导入后 Journey 查询。
- [x] 桌面 smoke 证明 TutorSession、消息、setting 和兼容 AgentState 随数据库恢复；不调用真实 OpenAI。
- [x] React 在重启后不保留导入前的 Journey、LearnUnit、Assessment、TutorSession 或 TutorEvent 状态。
- [x] 用户文档说明正式数据库位置、手动导出/导入流程、预导入备份位置、精确 schema 版本限制和旧快照覆盖规则。
- [x] 用户文档明确 OneDrive 和 Google Drive 当前仅作为用户自行选择的文件传输目录，不存在应用级云盘接入。
- [x] `pnpm check` 通过，覆盖前端 typecheck/lint/build、Rust 检查和 JVM 确定性测试。
- [x] Native Image、云盘 API、自动同步和跨 Agent runtime 的 AgentState 转换不作为本 ticket 的验收条件。

## Acceptance record

`pnpm desktop:smoke` 使用临时 `AGENT_DATA_DIR`、固定 `127.0.0.1:18080` 和空 `OPENAI_API_KEY`
，验证源库导出、目标库整库导入、预导入备份、目标数据删除、JVM PID 变化、端口恢复，以及
Journey、LearnUnit、TutorSession、消息、TutorEvent、setting 和 AgentState 的恢复。Tauri 的 stop/start 命令只管理同一 JVM
生命周期；数据库校验和替换仍由 Java HTTP API 完成。
