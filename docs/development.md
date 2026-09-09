# 开发

## 前置条件

安装 Node.js、pnpm、Rust 和 Java 21 JDK。JVM 后端使用仓库中的 Maven Wrapper；
Tauri 启动后端时要求 `java` 可在 PATH 中找到。

提供商凭据只设置在进程环境中：

```bash
export OPENAI_API_KEY=...
export OPENAI_MODEL=gpt-5-mini       # 可选
```

未设置 `OPENAI_API_KEY` 时 `pnpm dev` 仍可启动界面和后端，但 LLM 功能会明确提示未配置。

## 命令

```bash
pnpm install
pnpm dev             # Vite + JVM 后端 + Tauri 壳
pnpm check           # 前端、Rust 和 JVM 测试
pnpm desktop:smoke   # JVM 启动、固定端口和退出回收
pnpm package:desktop # JVM JAR + Tauri 桌面包
```

本地 API 固定为 `http://127.0.0.1:18080/api`，Vite 界面地址为
`http://127.0.0.1:1420`。

## Learning Journey 手工验证

依次调用 `POST /api/learning/journeys`、`GET /api/learning/journeys/{id}/learn-units`、
创建并启动 Diagnostic、逐题提交答案，再检查 `GET /api/learning/journeys/{id}` 返回的
path。创建 Journey 时需要用户提供目标语言，LLM 为该 Journey 独立生成 LearnUnit；同一
Journey 重启后直接从 SQLite 恢复。没有可用 LLM 时，题目规划会直接报错，不会回退到
已有 SQLite 题库或保存部分生成结果。

`TutorAgentToolLoopTest` 使用假的 `ChatModel` 验证 SAA ReactAgent 的工具循环，不访问
OpenAI。`desktop:smoke` 使用隔离数据目录检查 JVM 后端健康、固定端口和退出后的端口回收；
Native Image 不属于当前桌面验收范围。
