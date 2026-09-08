# 桌面学习 Agent

这是一个面向 Windows、macOS 和 Linux 的桌面学习 Agent MVP。

桌面壳使用 Tauri 2，界面使用 React + TypeScript + Vite。本地后端使用 Spring Boot
4.0.0、Spring AI Alibaba 2.0.0-M1.1 的 `ReactAgent`/Graph Core，以及 Spring AI
2.0.0-M1 的 OpenAI `ChatModel`。Learning Engine 负责确定性评分、路径和状态，
SQLite 是长期事实来源。

教学知识使用按 Journey 生成并持久化的 `LearnUnit`；`Skill` 只表示 Agent capability，
不表示课程内容。

## 本地运行

前置条件：Node.js 22+、pnpm、Rust、Maven Wrapper，以及用于 JVM 开发路径的 Java 21。
没有 `OPENAI_API_KEY` 也可以启动 dev，此时后端使用 disabled ChatModel；创建 Journey、Tutor 和其他 LLM 功能仍需配置真实 key。

```bash
pnpm install
export OPENAI_API_KEY=...
pnpm dev
```

`pnpm dev` 会启动 Spring 后端（`127.0.0.1:18080`）、Vite 界面
（`127.0.0.1:1420`）和 Tauri 壳。开发时使用 JVM 后端，打包版本使用 Native sidecar。

## 检查

```bash
pnpm check          # TypeScript、ESLint、Vite、Cargo、Maven 测试
pnpm native:build   # 构建 GraalVM Native Image 可执行文件
pnpm native:check   # 检查构建、启动、健康状态、SQLite、SAA 工具循环和 SSE
```

Native 命令要求使用安装了 `native-image` 的 GraalVM 25 JDK。当前机器的验证结果见
[docs/graalvm.md](docs/graalvm.md)，sidecar 命名见 [docs/packaging.md](docs/packaging.md)。

## API

所有 HTTP 接口仅绑定到 `127.0.0.1:18080`。除会话/SSE 接口外，学习流程位于
`/api/learning`，包含 Journey、Diagnostic、LearnUnit、Assessment、Attempt 和进度操作。

SQLite 默认路径为 `${user.home}/.desktop-learning-agent/agent.db`；可通过
`AGENT_DATA_DIR` 或 `APP_DATABASE` 覆盖。

## 范围边界

本仓库不包含认证、工作区沙箱、MCP、RAG、Monaco 编辑、多 Agent 路由、自动更新或随包
提供的 JRE。设计说明见 [docs/architecture.md](docs/architecture.md)，项目规则见
[AGENTS.md](AGENTS.md)。
