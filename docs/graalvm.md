# GraalVM 与 Native Image

Native Image、Native sidecar 和 Native Skill 资源适配不属于当前 AgentScope + Spring
Boot WebFlux JVM 迁移的验收门槛。当前正式目标是 macOS arm64 上的 Java 21 JVM 桌面链路；
Native、Windows/Linux 和其他 macOS 架构后置。

仓库仍保留 `pnpm native:build` 与 `pnpm native:check` 作为历史工具链入口，但它们不构成
issue 11 的通过证据，也不能代替 macOS arm64 + 真实 OpenAI 完整 E2E。若未来重新纳入范围，
应单独建立规格和验收记录。
