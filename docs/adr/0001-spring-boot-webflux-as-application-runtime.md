# ADR 0001: 使用 Spring Boot WebFlux 作为应用运行时边界

Status: accepted

Date: 2026-09-09

## Context

目标产品需要在 macOS arm64 上运行一个本地桌面编程学习 Agent。当前仓库已有 Spring Boot 应用、Spring MVC SSE、Spring
JDBC/JdbcClient 和 SQLite 实现；此前迁移规格曾将 Quarkus JVM 作为应用运行时目标。

Agent runtime 与应用框架需要保持分离：AgentScope 负责 TutorAgent、Harness、Skill、AgentState、工具生命周期和 Agent 事件；应用框架负责
HTTP、SSE、SQLite、Learning Engine 和 Tauri 进程边界。学习评分、通过、重试、跳过和路径不能由 Agent runtime 决定。

SSE 需要支持文本增量、safe reasoning 摘要、Skill 加载状态、工具进度、错误、取消、完成、背压和断线重放。SQLite 继续是唯一 durable
database，但 JDBC 是阻塞 API，不能直接占用 WebFlux event loop。

## Decision

使用 Java 21 + Spring Boot 4.0.0 + Spring WebFlux 作为目标应用运行时边界，直接将 AgentScope Java 2.x 集成为 Spring
Bean。应用只保留一个 `TutorAgent`，其 runtime 使用 AgentScope `HarnessAgent`；当前模型 provider 使用 AgentScope OpenAI
provider。

SSE 使用 WebFlux 原生响应流和项目自有、框架无关的 `TutorEvent` DTO。AgentScope 内部事件必须经过薄适配层投影，不能直接泄漏到
HTTP 或 React/Tauri。

SQLite 继续使用 Xerial JDBC 和 Spring JDBC/JdbcClient。所有 JDBC、AgentState 和事务等阻塞操作必须隔离到工作线程，不引入
R2DBC、JPA 或 Hibernate 作为本次替代方案。

AgentScope `HarnessAgent` 和官方 OpenAI provider 是唯一 Agent runtime 与模型接入。旧 runtime、旧
adapter 和旧 schema 不属于兼容边界；不保留运行时 fallback，也不兼容旧数据库或旧 AgentState。

Native Image、其他平台和 Native Skill 资源适配不属于本次 Definition of Done，后续另行决策。

## Consequences

- 可以复用当前 Spring Boot、SQLite、Spring JDBC 和本地桌面边界，迁移范围更小。
- WebFlux 能直接承接 AgentScope 的流式事件，并保留取消和背压语义。
- JDBC 阻塞隔离必须成为实现和测试的明确约束；不能把阻塞数据库调用放进 WebFlux event loop。
- 旧 runtime 已删除；发现旧数据库、旧 schema 或旧 AgentState 时直接报错并要求使用新数据库。
- Quarkus 规格和任务不再是实现依据，Spring Boot WebFlux 规格成为后续任务的唯一来源。
