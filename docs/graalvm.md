# GraalVM 与 Native Image

## 目标工具链

- Java 源码/目标版本：21
- GraalVM：25 或更高版本
- Spring Boot：4.1.1
- Native 构建：`./backend/mvnw -Pnative native:compile`

Spring Boot 4.1.x 支持 Java 17 或更高版本，并针对 Native Image 使用 GraalVM 25
或更高版本。Maven Wrapper 固定使用 Maven 3.9.9，因此开发者不需要全局安装 Maven。

## 当前验证

开发 JDK 仍为 Java 21.0.7。Native Image 使用临时安装的 Oracle GraalVM 25.0.4
验证，未改变系统默认 JDK。构建产物如下：

* `backend/target/agent-backend`（约 220MB，macOS arm64）
* Native Image 构建耗时：6 分 06 秒；Maven 目标耗时：6 分 15 秒
* 构建参数：`-O0`、`--parallelism=4`、`-J-Xmx12g`、Serial GC

Native Image 构建默认限制为 4 个编译线程和 12 GB 堆。可根据构建机器规格覆盖
任一限制：

```bash
./backend/mvnw -Pnative \
  -Dnative.image.parallelism=2 \
  -Dnative.image.max-heap=4g \
  native:compile
```

Native 可执行文件约 0.4 秒启动。构建完成后，
`NATIVE_CHECK_SKIP_BUILD=true pnpm native:check` 通过了所有运行时边界检查：健康状态、
SQLite 会话读写、ADK `Runner` 和 `EchoTool` 调用/结果循环、持久化空闲 SSE、流式工具
调用/结果事件、工具结果重发，以及最终 Assistant 消息持久化。检查使用本地
OpenAI 兼容 HTTP stub，不会访问 OpenAI。

安装好工具链后运行：

```bash
pnpm native:build
pnpm native:check
```

`native:check` 使用隔离的临时 `AGENT_DATA_DIR` 启动生成的可执行文件，调用健康检查，
创建并列出会话以验证 SQLite 读写，运行 Native ADK fixture，打开持久化空闲 SSE 流，
然后针对本地 OpenAI 兼容 stub 通过 HTTP 接口发送真实消息。它会验证流式模型请求、
ADK `tool_call`/`tool_result` 事件、非空的 Native 事件 JSON、工具结果重发和最终
Assistant 消息持久化。迭代时如需复用已有二进制：

```bash
NATIVE_CHECK_SKIP_BUILD=true pnpm native:check
```

## 已知兼容性风险

当前构建会输出非阻塞警告，原因包括：reachability metadata 仓库没有若干 Spring
Boot 4.1.1 和 Spring AI 2.0.1 构件的精确条目，以及传递依赖 metadata 含有面向测试的
Byte Buddy/Gradle 类的已弃用代理配置。Native SQLite loader 启动时还会输出 Java 的
受限 `System.load` 警告。这些警告都没有阻止已验证的 Native 运行时启动或通过检查。

依赖变更后，Native Image 仍可能在提供商 SDK、Jackson 多态模型类型、RxJava 回调、
SQLite JDBC Native 加载或基于反射的 ADK `FunctionTool` 注册处暴露缺少 reachability
metadata 的问题。只修复构建报告的具体反射、资源或代理 hint，然后重新运行 Native
冒烟检查；不要为此替换 ADK 或 Spring AI，或引入第二套运行时。`-O0` 配置是第一阶段
对构建时间的取舍；发布生产安装包前，应使用单独的 release 配置、经过测量的优化参数
和跨平台 CI。
