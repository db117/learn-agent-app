# 桌面学习 Agent

这是一个以 macOS arm64 为正式目标的桌面学习 Agent MVP。

桌面壳使用 Tauri 2，界面使用 React + TypeScript + Vite。本地后端使用 Spring Boot
4.0.0、Spring AI Alibaba 2.0.0-M1.1 的 `ReactAgent`/Graph Core，以及 Spring AI
2.0.0-M1 的 OpenAI `ChatModel`。Learning Engine 负责确定性评分、路径和状态，
SQLite 是长期事实来源。

教学知识使用按 Journey 生成并持久化的 `LearnUnit`；`Skill` 只表示 Agent capability，
不表示课程内容。

## 本地运行

前置条件：Node.js 22+、pnpm、Rust、Maven Wrapper，以及 Java 21。
没有 `OPENAI_API_KEY` 也可以启动 dev，此时后端使用 disabled ChatModel；创建 Journey、Tutor 和其他 LLM 功能仍需配置真实 key。

```bash
pnpm install
export OPENAI_API_KEY=...
pnpm dev
```

`pnpm dev` 会启动 Vite 界面（`127.0.0.1:1420`）和 Tauri 壳；Tauri
负责启动并回收 Spring Boot JVM 后端（`127.0.0.1:18080`）。

## 检查

```bash
pnpm check          # TypeScript、ESLint、Vite、Cargo、Maven 测试
pnpm desktop:smoke  # JVM 启动、固定端口和退出回收
pnpm package:desktop # 构建 JVM JAR 并打包 Tauri 应用
```

当前桌面包使用 JVM JAR，不包含 JRE；运行包的机器需要可用的 Java 21。
Native Image、Native sidecar 和其他平台构建不属于当前验收范围。

## API

所有 HTTP 接口仅绑定到 `127.0.0.1:18080`。除会话/SSE 接口外，学习流程位于
`/api/learning`，包含 Journey、Diagnostic、LearnUnit、Assessment、Attempt 和进度操作。

SQLite 默认路径为 `${user.home}/.desktop-learning-agent/agent.db`；可通过
`AGENT_DATA_DIR` 或 `APP_DATABASE` 覆盖。

## 范围边界

本仓库不包含认证、工作区沙箱、MCP、RAG、Monaco 编辑、多 Agent 路由、自动更新或随包
提供的 JRE。设计说明见 [docs/architecture.md](docs/architecture.md)，项目规则见
[AGENTS.md](AGENTS.md)。
