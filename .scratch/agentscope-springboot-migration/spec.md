# AgentScope Java 2.0 + Spring Boot WebFlux 学习代理迁移规格

Triage: ready-for-human

Status: issue 11 local checks complete; macOS arm64 + real OpenAI acceptance blocked

Scope: macOS arm64、Java 21、Spring Boot 4.0.0、Spring WebFlux、AgentScope Java 2.0.3、SQLite、React/Tauri

Artifact purpose: 需求与实现规格整理。本规格不包含本轮代码实现。

## Problem Statement

当前仓库已经具备 Spring Boot、Spring MVC SSE、Spring JDBC/JdbcClient、SQLite 和 Tauri/React 边界，但 Agent runtime 仍依赖
Spring AI、Spring AI Alibaba 和旧适配层。此前规格曾将 Quarkus JVM 作为目标应用运行时，现已决定取消 Quarkus，继续使用 Spring
Boot，并将 HTTP/SSE 层切换为 Spring WebFlux。

如果只替换框架名称而不重新确定边界，会产生以下风险：

- Spring Boot 应用框架、AgentScope runtime、AgentScope Skill 和 LearnUnit 被混为一谈。
- WebFlux event loop 直接执行 SQLite JDBC 或其他阻塞操作，造成阻塞和取消失效。
- AgentScope 内部事件、reasoning、系统提示词和敏感参数被直接暴露给 React/Tauri。
- 旧 Spring AI/Spring AI Alibaba/ADK 链路与新 AgentScope 链路形成长期双运行时或隐式 fallback。
- LLM 生成失败、AgentState 恢复失败、旧 schema 和 SSE 断线时的行为不明确。
- 旧 Quarkus 规格和任务仍可能被 Agent 抓取执行，导致实现依据分裂。

本次工作需要形成一条单一、可验收的新链路：macOS arm64 上运行 Java 21 + Spring Boot WebFlux JVM 应用；AgentScope Java
`HarnessAgent` 是唯一 Agent runtime；Learning Engine 使用确定性 Java 逻辑；React/Tauri 通过项目自有 HTTP/SSE DTO 与后端通信。

本次迁移是替换而不是兼容层建设。新链路完整通过人工 E2E 后，删除旧 Spring AI、Spring AI Alibaba、ADK 和相关 adapter；不兼容旧数据库、旧
schema 或旧 AgentState，也不保留旧 runtime fallback。

## Solution

使用 Spring Boot 4.0.0 + Spring WebFlux 承载目标应用边界，保持 Java 21。AgentScope Java 2.0.3 直接作为 Spring Bean
集成，应用只保留一个 `TutorAgent`，其 Agent runtime 使用 `HarnessAgent`，模型 provider 当前使用 AgentScope 官方 OpenAI
provider。

Spring WebFlux 负责 HTTP、SSE 和取消/背压边界。AgentScope 的流式事件经过薄适配层转换为项目自有、框架无关的 `TutorEvent`
，React/Tauri 不接触 AgentScope 内部事件类型。SSE 使用 WebFlux 原生响应流，支持文本增量、safe reasoning 摘要、Skill
加载进度、工具进度、错误、取消、完成和 Last-Event-ID 重放。

SQLite 仍是唯一 durable database，继续使用 Xerial JDBC 和 Spring JDBC/JdbcClient。数据库、AgentState 和事务操作属于阻塞工作，必须从
WebFlux event loop 隔离到工作线程；本次不引入 R2DBC、JPA 或 Hibernate。

Learning Journey 是学习入口。学习者提交目标语言、学习目标和背景后，LLM 按当前 Journey 生成独立的 LearnUnit、教学内容、诊断内容和适用
Question，并在 Java 校验后原子写入 SQLite。LearnUnit 数量没有 4–8 的硬上限；没有编码学习内容时不生成 Coding Question。LLM
失败直接报错，不回退、不保留部分结果。

AgentScope Skill 只表示工程能力，与 LearnUnit 没有业务关系；不使用 `LearningSkill` 或 `LearnerLearnUnit`。学习者的当前单元和进度只由
`LearningPathItem` 表示。Learning Engine 确定性控制评分、通过、Retry、Skip、Next、路径和完成状态，TutorAgent 只负责教学对话。

当前正式验收 seam 是 `127.0.0.1:18080` 上的 macOS arm64 Spring Boot WebFlux HTTP/SSE 完整 E2E。人工验收使用真实 OpenAI，CI
使用 Fake/Deterministic Model。Native、其他平台和 Native Skill 资源适配后置。

## User Stories

1. 作为学习者，我希望选择目标编程语言并提交学习目标，从而创建独立的 Learning Journey。
2. 作为学习者，我希望填写主要编程语言、经验年限和背景描述，从而让 TutorAgent 使用适合我的教学上下文。
3. 作为学习者，我希望由 LLM 为当前 Journey 生成 LearnUnit 和教学内容，从而获得针对目标的学习路径。
4. 作为学习者，我希望不同 Journey 使用独立的 LearnUnit 集合，从而避免课程内容和学习进度互相污染。
5. 作为学习者，我希望内容较多时可以生成超过 4–8 个 LearnUnit，从而保证覆盖完整目标。
6. 作为学习者，我希望 LearnUnit 是实际学习的最小单元，从而清楚知道当前学习内容。
7. 作为学习者，我希望没有编码内容时不生成 Coding Question，从而避免无意义的编程题。
8. 作为学习者，我希望 LLM 生成失败时看到明确错误，从而知道本次生成没有成功。
9. 作为学习者，我希望生成失败时不会留下部分 Journey、LearnUnit、Question 或路径，从而避免使用不完整数据。
10. 作为学习者，我希望系统校验 LLM 输出结构和题型覆盖，从而避免非法内容直接进入学习流程。
11. 作为学习者，我希望每个 LearnUnit 的诊断至少有两个有效证据项，从而避免单题误判能力。
12. 作为学习者，我希望看到确定性的 Learning Path，从而知道接下来学习什么。
13. 作为学习者，我希望 TutorAgent 知道当前学到哪里和下一步要做什么，从而获得连续教学。
14. 作为学习者，我希望同一 Journey + LearnUnit 重返时复用 TutorSession，从而继续原对话。
15. 作为学习者，我希望切换 LearnUnit 时使用隔离的 TutorSession，从而避免上下文污染。
16. 作为学习者，我希望应用重启后恢复 AgentState，从而继续之前的教学对话。
17. 作为学习者，我希望 AgentState 恢复失败时看到明确错误，从而不会被静默重置。
18. 作为学习者，我希望看到文本流式输出，从而能在回答完成前开始阅读。
19. 作为学习者，我希望看到安全的高层 reasoning 摘要，从而感受到 AI 正在处理问题而不暴露思维链。
20. 作为学习者，我希望看到 AgentScope Skill 加载和工具进度，从而了解教学能力的执行阶段。
21. 作为学习者，我希望 SSE 断线后可以重放缺失事件，从而不丢失学习反馈。
22. 作为学习者，我希望取消或关闭页面时 Agent 调用被取消，从而避免后台继续执行无用请求。
23. 作为学习者，我希望选择题按照固定答案和分值评分，从而每次结果一致。
24. 作为学习者，我希望编码题在存在时按结构化 rubric 评价，从而获得具体反馈和稳定分数。
25. 作为学习者，我希望 Assessment 重试时使用固定题集，从而历史结果保持可解释。
26. 作为学习者，我希望 Retry 保留历史 Attempt，从而看到真实学习过程。
27. 作为学习者，我希望可以 Skip 当前 LearnUnit，从而在暂时不学习时继续前进。
28. 作为学习者，我希望 Skip 与 Passed 保持不同状态，从而不会把跳过误认为掌握。
29. 作为学习者，我希望通过当前 LearnUnit 后进入下一个单元，从而继续 Learning Path。
30. 作为学习者，我希望最后一个 LearnUnit 通过后 Journey 完成，从而明确知道目标已完成。
31. 作为维护者，我希望 Spring WebFlux 的阻塞 SQLite 操作不占用 event loop，从而保持流式接口稳定。
32. 作为维护者，我希望 HTTP/SSE 只暴露项目 DTO，从而避免前端依赖 AgentScope 或旧 SAA 类型。
33. 作为维护者，我希望 CI 使用 Fake/Deterministic Model，从而无需真实密钥也能验证规则。
34. 作为维护者，我希望 macOS arm64 人工验收使用真实 OpenAI，从而证明真实 provider 链路可用。
35. 作为维护者，我希望完整 E2E 通过后删除旧 Agent runtime，从而避免双运行时长期并存。
36. 作为维护者，我希望不保存运行日志、Prompt、完整模型响应或密钥，从而减少敏感数据持久化。
37. 作为维护者，我希望需求、规格和任务使用本地 Markdown 管理，从而不依赖 GitLab。

## Implementation Decisions

### 运行时与依赖

- 目标应用运行时是 Java 21 + Spring Boot 4.0.0 + Spring WebFlux，基线沿用当前仓库的 Spring Boot 版本。
- AgentScope Java 2.0.3 直接集成为 Spring Bean；实际公开 API 以选定版本依赖和官方资料为准，不猜测方法签名。
- 应用只保留一个 `TutorAgent`，使用 AgentScope `HarnessAgent`；不新增
  AssessmentAgent、PlannerAgent、CoordinatorAgent、Subagent 或 Multi-Agent。
- AgentScope 官方 OpenAI provider 是当前唯一模型 provider。其他 provider 不在本次实现中接入。
- Spring Boot 只负责应用边界和生命周期；AgentScope 负责 Agent 编排、Skill、AgentState、工具生命周期和 Agent 事件。
- Spring AI、Spring AI Alibaba、ADK 和旧 adapter 属于 Legacy Agent Runtime，不属于新链路的最终 runtime。

### WebFlux、事件和桌面边界

- HTTP/SSE 使用 Spring WebFlux 原生响应流；项目自有 `TutorEvent` 是唯一对前端公开的事件语义。
- AgentScope 内部事件经过薄适配层投影为文本增量、reasoning 摘要、Skill 加载、工具进度、错误、取消和完成等安全事件。
- SSE 事件有单调递增序号，支持 Last-Event-ID 重放、背压和取消传播。发送前保存的仅是用于重放的最小脱敏事件，不是运行日志。
- React/Tauri 不依赖 AgentScope、Spring AI 或 Spring AI Alibaba 的消息类型。
- Tauri 只负责 Desktop Shell、Spring Boot JVM 进程启停和桌面能力；Agent 与 Learning Engine 保留在 Java 中。
- 固定后端地址为 `127.0.0.1:18080`，当前不增加认证、Token 或随机端口。

### 阻塞持久化与 SQLite

- SQLite 是唯一 durable database，使用 Xerial JDBC 和 Spring JDBC/JdbcClient。
- JDBC、AgentState、事务和其他阻塞操作不得直接运行在 WebFlux event loop；必须通过明确的工作线程调度边界执行。
- 不引入 R2DBC、JPA、Hibernate、PostgreSQL、MySQL、Redis 或 H2。
- AgentState 与 Learning Domain 共用数据库时保持语义分离。AgentState 只负责 TutorSession 的 runtime context，不能决定学习进度或评分。
- 新链路使用新数据库；发现旧 schema 时明确报错并要求重新初始化，不静默删除、覆盖、迁移或回退。

### Learning Domain 与 LLM

- `LearnUnit` 是唯一用户学习单元；不使用 `LearningSkill` 或 `LearnerLearnUnit`。Journey 内学习者状态由 `LearningPathItem`
  表示。
- AgentScope Skill 是工程能力包，与 LearnUnit 没有领域关系，不参与课程目录、学习进度、评分或通过判定。
- 每个 Journey 独立生成 LearnUnit、教学内容和题目，不共享课程目录；LearnUnit 数量没有固定上限。
- LLM 是生成 Journey、LearnUnit、诊断内容和必要 Question 的唯一生产来源。LLM 失败、超时、非法结构或校验失败时直接报错，不回退且不保存部分数据。
- 没有编码学习目标时不生成 Coding Question。Question 定义只能新增或 soft delete；题干、答案、分值和评分规则创建后不可更新。
- Assessment 只引用创建时固定的 Question 集合，历史 Attempt 可读，Retry 复用固定题集。
- Java Learning Engine 确定性负责评分、通过、Retry、Skip、Next、路径、进度和 Journey 完成。TutorAgent 不直接修改这些学习事实。

### TutorSession、TutorContext 与 AgentState

- 每个 Journey + LearnUnit 使用独立 TutorSession；同一组合重入复用，切换 LearnUnit 隔离。
- 每次 Tutor 调用从最新 Learning Journey、LearnUnit、LearningPathItem 和 Assessment history 组装只读 TutorContext。
- AgentState 仅保存对话、Harness 和工具执行上下文；恢复失败返回明确错误，不静默新建空会话。
- 删除 TutorSession 不删除 Learning Journey、LearningPathItem、AssessmentAttempt 或分数；恢复 AgentState 不改变学习事实。

### Cutover

- 当前切换条件是 macOS arm64 Spring Boot WebFlux + AgentScope JVM 完整 HTTP/SSE E2E 通过。
- 完整验收前保留 Legacy Agent Runtime；它不是 fallback，也不需要读取新数据。
- 新链路验收通过后删除 Spring AI、Spring AI Alibaba、ADK、旧 Agent runtime、旧 adapter 和旧 event adapter。
- 不做旧后端、旧 SQLite 数据、旧 schema 或旧 AgentState 的兼容、迁移和回滚。

## Testing Decisions

### 主验收 seam

主 seam 是 macOS arm64 上固定 `127.0.0.1:18080` 的 Spring Boot WebFlux HTTP/SSE 完整用户旅程，人工使用真实 OpenAI。必须覆盖：

1. Spring Boot WebFlux 启动并初始化新 SQLite。
2. 学习者提交目标和背景，LLM 生成 Journey、LearnUnit、内容和适用 Question。
3. 生成结果经 Java 校验并原子保存；无编码内容时没有 Coding Question。
4. Diagnostic、Learning Path、TutorSession、TutorContext 和 AgentScope Skill 加载运行。
5. SSE 展示文本、safe reasoning、Skill/工具进度、完成和错误；不暴露内部事件或敏感内容。
6. 关闭并重启后恢复 Learning state、TutorSession 和 AgentState。
7. Assessment、评分、Retry、Skip、Next 和 Journey Completed 按确定性规则运行。
8. SSE 重连、Last-Event-ID、取消、provider error、LLM 生成失败、恢复失败和旧 schema 都有明确结果。
9. React/Tauri 通过固定 loopback 完成桌面端链路；健康检查或单接口成功不算完成。

### CI 和专项测试

- CI 使用 Fake/Deterministic Model；它只替代 provider 网络行为，不是生产 fallback。
- Learning Engine、评分、Workflow、Assessment、SQLite transaction、Question immutable、TutorContext、TutorSession 隔离和
  AgentState 恢复使用确定性测试。
- 测试 WebFlux 事件流的序号、背压、Last-Event-ID 重放、取消传播、错误和终止事件。
- 测试 JDBC 和 AgentState 操作不会在 WebFlux event loop 执行；阻塞操作使用明确的工作线程边界。
- 测试 LLM 非法 JSON、字段缺失、题型不足、超时、provider error 和中断，不产生半完成 Journey 或 Attempt。
- 测试 AgentScope HarnessAgent、RuntimeContext、Skill discovery/load、streamEvents 和 SQLite AgentState store。
- 前端、Tauri 和后端完整构建变更后执行项目检查；Native 检查不属于当前验收。
- 完整 E2E 通过后才执行旧链路删除前的最终构建和全量测试。

### Seam 选择理由

WebFlux HTTP/SSE E2E 能同时验证 Spring Boot 应用边界、AgentScope runtime、OpenAI、SQLite、阻塞隔离、TutorEvent、Learning
Engine、React/Tauri 和重启恢复。确定性测试用于快速验证规则和失败边界，但不能代替真实 macOS arm64 provider E2E。

## Out of Scope

- Quarkus、GraalVM Native Image、Mandrel、Native sidecar 和 Native Skill 资源适配。
- Windows、Linux、其他 macOS 架构和跨平台 binary 构建。
- 旧 Spring AI/Spring AI Alibaba/ADK backend、旧 schema、旧数据和旧 AgentState 的兼容、迁移、双写或回滚。
- 运行时 fallback、静态课程 fallback、旧题库 fallback 或用 Mock 绕过生产 LLM 失败。
- Gemini、Anthropic、Ollama 等非 OpenAI provider。
- Multi-Agent、Subagent、Plan Mode、Agent self-learning、Agent-generated Skill 和长期语义记忆。
- MCP、RAG、向量数据库、云同步、账户、认证、授权、Token、加密、Stronghold、工具审批和工作区沙箱。
- Workspace、文件树、Monaco、Shell、编译代码、运行代码、运行测试和 Coding Workspace。
- Android、iOS、自动更新和跨设备同步。
- 持久化运行日志、Prompt、完整模型响应、密钥、原始思维链或敏感参数。
- GitLab Issue、外部任务同步和外部需求管理系统。

## Further Notes

- 本规格取代所有 Quarkus 目标规格和实现任务；Spring Boot WebFlux 规格是后续实现的唯一来源。
- “Spring Boot WebFlux Application”是应用框架边界；“AgentScope Skill”是 Agent 工程能力；“LearnUnit”是学习者最小学习单元，三者不能互换。
- WebFlux 选择不意味着所有业务必须异步化；SQLite/JDBC 可以继续使用，但阻塞操作必须显式隔离出 event loop。
- Fake/Deterministic Model 只属于测试 seam；生产环境仍强依赖 LLM，provider 失败必须直接报错。
- Native 和跨平台如果未来重新进入范围，应创建独立规格和任务，不得改变本次 JVM cutover 的验收条件。
