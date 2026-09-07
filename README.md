# 桌面学习 Agent

这是一个面向 Windows、macOS 和 Linux 的桌面学习 Agent 第一阶段 MVP。

桌面壳使用 Tauri 2，界面使用 React + TypeScript + Vite。本地后端是
Spring Boot 4.1.1 Native Image 应用。Google ADK 1.9.0 负责 Agent 编排、会话、
事件和工具执行；Spring AI 2.0.1 仅提供 OpenAI `ChatModel` 的提供商适配层。

## 本地运行

前置条件：Node.js 22+、pnpm、Rust、Maven Wrapper，以及用于 JVM 开发路径的
Java 21。发送真实消息前，请设置 `OPENAI_API_KEY`。

```bash
pnpm install
export OPENAI_API_KEY=...
pnpm dev
```

`pnpm dev` 会启动 Spring 后端（`127.0.0.1:18080`）、Vite 界面
（`127.0.0.1:1420`）和 Tauri 壳。Tauri 壳保持轻量：开发时单独启动 JVM 后端，
打包版本使用 Native sidecar。

## 检查

```bash
pnpm check          # TypeScript、ESLint、Vite、Cargo、Maven 测试
pnpm native:build   # 构建 GraalVM Native Image 可执行文件
pnpm native:check   # 检查构建、启动、健康状态、SQLite、ADK 工具循环和 SSE
```

Native 命令要求使用安装了 `native-image` 的 GraalVM 25 JDK。
当前机器的验证结果见 [docs/graalvm.md](docs/graalvm.md)，sidecar 命名见
[docs/packaging.md](docs/packaging.md)。

## API

所有 HTTP 接口仅绑定到 `127.0.0.1:18080`：

- `GET /api/health`
- `GET /api/sessions`
- `POST /api/sessions`
- `GET /api/sessions/{sessionId}`
- `POST /api/sessions/{sessionId}/messages`
- `GET /api/sessions/{sessionId}/events`（SSE）

SQLite 默认路径为 `${user.home}/.desktop-learning-agent/agent.db`；可通过
`AGENT_DATA_DIR` 或 `APP_DATABASE` 覆盖。

## 范围边界

本仓库的范围止于第一阶段，不包含认证、工作区沙箱、MCP、RAG、Monaco 编辑、
多 Agent 路由、自动更新或随包提供的 JRE。

设计说明见 [docs/architecture.md](docs/architecture.md)，重点实现规则见
[AGENTS.md](AGENTS.md)。
