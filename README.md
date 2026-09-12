# 桌面学习 Agent

这是一个以 macOS arm64 为正式目标的 Tauri 2 + React + Spring Boot WebFlux JVM
学习 Agent MVP。学习领域使用 Journey-scoped `LearnUnit`，Java Learning Engine
确定性负责评分、路径和状态；`Skill` 只表示 Agent capability。

Tutor/SSE/AgentState/Tauri 主路径和学习内容生成统一使用 AgentScope；
AgentScope `HarnessAgent` 是唯一 Agent runtime，不保留旧 runtime fallback。

## 本地运行

前置条件：Node.js 22+、pnpm、Rust、Maven Wrapper，以及 Java 21。凭据只放在进程环境中：

```bash
pnpm install
export OPENAI_API_KEY=...
pnpm dev
```

没有 `OPENAI_API_KEY` 也可以启动 dev；需要 LLM 的操作会明确报错，不会回退到静态课程或旧题库。
`pnpm dev` 启动 Vite（`127.0.0.1:1420`）和 Tauri；Tauri 管理 Spring Boot JVM
后端（`127.0.0.1:18080`）的启停。

## 检查

```bash
pnpm check           # TypeScript、ESLint、Vite、Cargo、JVM 测试
pnpm desktop:smoke  # JVM 启动、固定端口和退出回收
pnpm package:desktop # JVM JAR + Tauri 桌面包
```

桌面包包含 JVM JAR，不包含 JRE；运行包的机器需要 Java 21 且 `java` 在 PATH 中。
Native、其他平台和 macOS arm64 手工验收不由 Windows 检查替代。

## API 与数据

所有 HTTP 接口仅绑定到 `127.0.0.1:18080`。除会话/SSE 接口外，学习流程位于
`/api/learning`，包含 Journey、Diagnostic、LearnUnit、Assessment、Attempt 和进度操作。

SQLite 默认路径为 `${user.home}/.learning-agent-java/agent.db`；可通过 `AGENT_DATA_DIR`
或 `APP_DATABASE` 覆盖。发现旧 schema 或旧 AgentState 时明确报错并要求使用新数据库路径，
不迁移、不覆盖、不重置。

## 范围边界

本仓库不包含认证、工作区沙箱、MCP、RAG、多 Agent 路由、自动更新或随包提供的 JRE。
设计说明见 [docs/architecture.md](docs/architecture.md)，项目规则见 [AGENTS.md](AGENTS.md)。
