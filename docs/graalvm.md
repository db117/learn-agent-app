# GraalVM 与 Native Image

## 目标工具链

- Java 源码/目标版本：21
- Spring Boot：4.0.0
- Spring AI：2.0.0-M1
- Spring AI Alibaba：2.0.0-M1.1
- GraalVM：25 或更高版本，并包含 `native-image`
- Native 构建：`./backend/mvnw -Pnative native:compile`

Maven Wrapper 固定使用 Maven 3.9.9，因此开发者不需要全局安装 Maven。Native 是正式
生产后端验收，不是 Phase 0 门禁。

## 当前验证

`pnpm check` 已验证 JVM 后端、React 和 Rust 路径。当前机器的 `JAVA_HOME` 指向
Eclipse Adoptium JDK 21，未安装 `native-image`；因此最近一次 `pnpm native:check` 在
Native 编译前被环境阻塞，不能将 JVM 通过结果描述为 Native 成功。

安装 GraalVM 后运行：

```bash
pnpm native:build
pnpm native:check
```

`native:check` 使用隔离的临时 `AGENT_DATA_DIR` 和本地 OpenAI 兼容 HTTP stub，验证
Native 后端启动、health、SQLite 会话读写、SAA ReactAgent 工具循环、持久化空闲 SSE、
流式工具调用/结果事件、工具结果重发和最终 Assistant 消息持久化。

构建参数默认限制为 4 个编译线程和 12 GB 堆，可按机器规格覆盖：

```bash
./backend/mvnw -Pnative \
  -Dnative.image.parallelism=2 \
  -Dnative.image.max-heap=4g \
  native:compile
```

依赖变更后只修复 Native 构建实际报告的 reachability、资源、序列化或代理问题，然后
重新运行 Native 冒烟检查；不引入第二套 Agent runtime。
