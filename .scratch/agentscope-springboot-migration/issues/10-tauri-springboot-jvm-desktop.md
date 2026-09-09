# 10: Tauri + Spring Boot WebFlux JVM 桌面链路

**What to build:** Tauri 可以启动、连接和停止 Spring Boot WebFlux JVM 后端，React 在桌面应用中通过固定 loopback
地址完成已实现的学习旅程；Tauri 不承载 Agent 或学习业务。

**Blocked by:** 08: Learning Workflow：Retry、Skip、Next、Completed；09: WebFlux SSE 重放、取消与错误处理

**Status:** resolved

- [x] Tauri 启动后端 JVM 进程并等待 `127.0.0.1:18080` 可用，前端连接失败时显示明确错误。
- [x] Tauri 关闭时能停止或回收后端进程，不把 Agent、Learning Engine、评分或 Workflow 逻辑放入 Rust。
- [x] React 在桌面环境中可完成目标选择、Journey 生成、Diagnostic、Learning Path、Tutor、Assessment、Retry/Skip/Next 和重启恢复。
- [x] 桌面链路使用项目自有 DTO/TutorEvent，不依赖 AgentScope、Spring AI 或 Spring AI Alibaba 内部事件类型。
- [ ] macOS arm64 的 Tauri、前端和 Spring Boot WebFlux JVM 检查通过；不引入 Native executable、Native sidecar 或其他平台要求。
- [x] 自动化或手工 smoke test 能证明桌面端从启动到退出的进程生命周期和固定端口连接正常。

## Answer

- Tauri Rust 壳改为管理 `java -jar agent-backend.jar`：启动时等待固定的 `127.0.0.1:18080`，启动失败通过 `backend-required` 事件和 `start_backend` 调用返回明确的 Java 21/PATH 错误；退出时停止并等待受管理的 JVM 子进程。已占用端口的外部后端不会被误杀。
- JAR 由 Maven 生成固定名称并作为 Tauri resource 打包；开发、打包和 `desktop:smoke` 命令统一复用该 JAR。React 继续使用项目自有 HTTP DTO 和 `TutorEvent`，Rust 不包含 Agent、Learning Engine、评分或 Workflow 逻辑。
- 新增 `pnpm desktop:smoke`，使用隔离 SQLite 数据目录验证 JVM 启动、健康接口、固定端口连接和退出后的端口回收；Tauri debug/NSIS 构建验证资源进入桌面包。
- Windows x64 的 Tauri dev 观察到真实 JVM 子进程和 `127.0.0.1:18080` 可用；停止 dev 后 JVM 子进程和端口均已回收。当前用户目录中的旧 SQLite schema 按既有规则明确拒绝启动，未覆盖旧数据；隔离新库的桌面 smoke 已通过。
- 当前环境是 Windows x64，不是 macOS arm64；已验证的 Windows 跨平台边界不能替代 macOS arm64 专项检查，因此该平台验收保留为外部阻塞，未声称通过。未引入 Native executable、Native sidecar、JRE 或其他平台要求。

## Comments

- 2026-09-09：仅处理 issue 10；未修改 issue 06-09，也未实现 issue 11。详见提交中的测试和平台限制记录。
