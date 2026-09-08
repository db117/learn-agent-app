# 开发

## 前置条件

安装 Node.js、pnpm、Rust 和 Java 21 JDK。JVM 开发后端使用仓库中的 Maven Wrapper；
生产后端需要安装带有 `native-image` 的 GraalVM 25，详见 [graalvm.md](graalvm.md)。

提供商凭据只设置在进程环境中：

```bash
export OPENAI_API_KEY=...
export OPENAI_MODEL=gpt-5-mini       # 可选
```

## 命令

```bash
pnpm install
pnpm dev             # Vite + JVM 后端 + Tauri 壳
pnpm check           # 前端、Rust 和 JVM 测试
pnpm native:build    # Native Image 后端
pnpm native:check    # Native 运行时冒烟测试
```

本地 API 固定为 `http://127.0.0.1:18080/api`，Vite 界面地址为
`http://127.0.0.1:1420`。

## Learning Journey 手工验证

依次调用 `POST /api/learning/journeys`、`GET /api/learning/journeys/{id}/learn-units`、
创建并启动 Diagnostic、逐题提交答案，再检查 `GET /api/learning/journeys/{id}` 返回的
path。创建 Journey 时需要用户提供目标语言，LLM 为该 Journey 独立生成 LearnUnit；同一
Journey 重启后直接从 SQLite 恢复。没有可用 LLM 时，题目规划只允许从已有 SQLite 题库
确定性回退；新 Journey 没有可回退课程时会失败并等待 LLM 可用。

`TutorAgentToolLoopTest` 使用假的 `ChatModel` 验证 SAA ReactAgent 的工具循环，不访问
OpenAI。`native:check` 针对实际 Native 可执行文件检查健康状态、SQLite、SSE、流式模型
请求、工具结果重发、事件序列化和最终 Assistant 消息持久化。
