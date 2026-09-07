# 开发

## 前置条件

安装 Node.js、pnpm、Rust 和 Java 21 JDK。JVM 开发后端使用仓库中的 Maven Wrapper。
生产后端需要安装带有 `native-image` 的 GraalVM 25，详见
[graalvm.md](graalvm.md)。

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

只检查某一层：

```bash
pnpm dev:web
pnpm dev:backend
pnpm typecheck
pnpm lint
pnpm build
pnpm cargo:check
pnpm backend:test
```

本地 API 有意固定为 `http://127.0.0.1:18080/api`。Vite 界面地址为
`http://127.0.0.1:1420`。

## 测试边界

`TutorAgentToolLoopTest` 是确定性的，不会访问 OpenAI。它使用假的 `ChatModel` 验证
由 ADK 负责的工具循环。`native:check` 会针对实际 Native 可执行文件检查运行时健康
状态、SQLite、持久化 SSE、流式 OpenAI 兼容请求、工具结果重发、Native 事件序列化和
最终 Assistant 持久化。真实 Assistant 消息需要 `OPENAI_API_KEY`。
