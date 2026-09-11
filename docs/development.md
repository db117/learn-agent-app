# 开发

## 前置条件

安装 Node.js、pnpm、Rust 和 Java 21 JDK。后端使用仓库中的 Maven Wrapper；Tauri 启动
JVM 时要求 `java` 在 PATH 中。凭据只设置在进程环境中：

```bash
export OPENAI_API_KEY=...
export OPENAI_MODEL=gpt-5-mini       # 可选
```

未设置 `OPENAI_API_KEY` 时 `pnpm dev` 仍可启动界面和后端，但 LLM 操作会明确失败，不会
回退到静态课程、旧题库或 Mock 结果。
`pnpm dev` 默认使用项目内的 `.data/dev/agent.db`；设置 `APP_DATABASE` 或 `AGENT_DATA_DIR`
可覆盖这个开发环境默认值。

后端日志默认写入 `${app.data-dir}/backend.log`（桌面模式通常为
`~/.learning-agent-java/backend.log`）；可用 `APP_LOG_FILE` 指定完整路径。结构化 LLM 调用会以 `[LLM-TRACE]`
记录完整 prompt、response format、响应块和最终模型原文，便于区分模型输出与代码解析问题；不会记录 API key。

## 命令

```bash
pnpm install
pnpm dev             # Vite + JVM 后端 + Tauri 壳
pnpm check           # 前端、Rust 和 JVM 确定性测试
pnpm desktop:smoke   # JVM 启动、固定端口和退出回收
pnpm package:desktop # JVM JAR + Tauri 桌面包
```

本地 API 固定为 `http://127.0.0.1:18080/api`，Vite 地址为
`http://127.0.0.1:1420`。CI 测试用 Fake/Deterministic Model 代替 provider 网络行为；
它不是生产 fallback。

## Learning Journey 手工验证

按顺序调用 `POST /api/learning/journeys`、`GET /api/learning/journeys/{id}/learn-units`、
创建并启动 Diagnostic、逐题提交答案，再检查 `GET /api/learning/journeys/{id}` 的 Path。
创建 Journey 时必须由用户提供目标语言；LLM 为当前 Journey 独立生成 LearnUnit 和题目，
重启后从 SQLite 恢复。生成失败、题目结构非法、AgentState 恢复失败和旧 schema 都必须
得到明确错误。

Tutor 的确定性 WebFlux E2E 覆盖 HarnessAgent、Skill、安全事件、SSE 重放、取消、错误和
AgentState 恢复；`desktop:smoke` 只覆盖 JVM 桌面进程生命周期。macOS arm64 + 真实 OpenAI
完整用户旅程仍需在 macOS 上手工执行，Windows 检查不能替代它。Native 不属于当前验收门槛。
