# JVM 桌面打包

当前桌面形态是 Tauri + Spring Boot WebFlux JVM。应用包包含前端和
`agent-backend.jar`，不包含 JRE；运行包的机器需要 Java 21，且 `java` 在 PATH 中。
macOS arm64 是正式迁移目标，但仍需独立的真实 OpenAI E2E 验收。

## 构建

```bash
pnpm package:desktop
```

命令先用 Maven 构建 `backend/target/agent-backend.jar`，再由
`src-tauri/tauri.conf.json` 将 JAR 作为 Tauri resource 打入应用；PNG 和 ICO 图标都由
同一配置声明。

## 运行时生命周期

Rust 壳只管理 JVM 子进程，并暴露 `backend_status`、`start_backend` 和 `stop_backend`
命令。启动时执行 `java -jar agent-backend.jar`，等待固定的 `127.0.0.1:18080` 可用；
启动失败会传给 React 显示。Tauri 退出时停止并等待受管理的 JVM 子进程；已由其他进程
占用的后端不会被壳误杀。

`pnpm desktop:smoke` 使用隔离 SQLite 数据目录验证 JVM 启动、健康接口、固定端口和退出
后的端口回收。Native executable、Native sidecar、JRE 打包和其他平台构建不属于当前迁移
验收范围。
