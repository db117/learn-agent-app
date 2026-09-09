# 10: Tauri + Spring Boot WebFlux JVM 桌面链路

**What to build:** Tauri 可以启动、连接和停止 Spring Boot WebFlux JVM 后端，React 在桌面应用中通过固定 loopback
地址完成已实现的学习旅程；Tauri 不承载 Agent 或学习业务。

**Blocked by:** 08: Learning Workflow：Retry、Skip、Next、Completed；09: WebFlux SSE 重放、取消与错误处理

**Status:** ready-for-agent

- [ ] Tauri 启动后端 JVM 进程并等待 `127.0.0.1:18080` 可用，前端连接失败时显示明确错误。
- [ ] Tauri 关闭时能停止或回收后端进程，不把 Agent、Learning Engine、评分或 Workflow 逻辑放入 Rust。
- [ ] React 在桌面环境中可完成目标选择、Journey 生成、Diagnostic、Learning Path、Tutor、Assessment、Retry/Skip/Next 和重启恢复。
- [ ] 桌面链路使用项目自有 DTO/TutorEvent，不依赖 AgentScope、Spring AI 或 Spring AI Alibaba 内部事件类型。
- [ ] macOS arm64 的 Tauri、前端和 Spring Boot WebFlux JVM 检查通过；不引入 Native executable、Native sidecar 或其他平台要求。
- [ ] 自动化或手工 smoke test 能证明桌面端从启动到退出的进程生命周期和固定端口连接正常。
