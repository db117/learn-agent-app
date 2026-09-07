# Findings & Decisions

## Requirements
- 用户要求在现有 Phase 1 项目继续完成 Phase 2 Learning Core，不重建项目，不实现 Phase 3。
- 强制保持 ADK 为 Agent runtime、Spring AI 只做 provider、SQLite + Spring JDBC、固定 `127.0.0.1:18080`、GraalVM Native 可构建。
- 主入口改成 Learning Journey；TutorAgent 只负责交互上下文，不决定确定性进度/评分。
- MVP 至少覆盖 TypeScript 6 个可演示 LearningSkill、诊断、MC/CODING 两种题型、确定性 Score Engine、LLM coding evaluator 抽象、Path/Progress/Skip/Retry/Mastery、SQLite 恢复和前端主流程。

## Research Findings
- 当前仓库文件很少，后端 package 为 `com.example.agent`；现有代码集中在 session/chat、ADK TutorAgent、Spring AI、SQLite schema。
- 初始 `git diff --stat` 只有 `src-tauri/icons/icon.png` 二进制变更；该用户已有变更必须保留。
- 已初始化根目录 `task_plan.md`、`findings.md`、`progress.md` 作为本任务持久化工作记忆。
- 文档确认：固定后端地址 `127.0.0.1:18080`；ADK `Runner/LlmAgent/Session/Event/Tool` 是唯一 Agent 运行时；Spring AI 适配必须留在 `llm/infrastructure`；SQLite 通过 `SqliteRepository` + `JdbcClient`；生产形态为 GraalVM Native sidecar。
- Phase 1 当前持久化表为 `session`、`message`、`agent_run`、`event`、`setting`；ADK 运行时会话在内存，应用链路记录落 SQLite。
- `pnpm check` 覆盖 TypeScript、ESLint、Vite、Cargo、Maven 测试；`pnpm native:check` 负责 Native 构建/启动和运行时冒烟，修改后必须按 AGENTS 执行。
- `backend/pom.xml` 目前只有 Web/JDBC/Spring AI OpenAI/ADK/SQLite/Test 依赖；不需要为 Phase 2 增加框架或数据库。
- 当前 React `App.tsx` 是单一 Phase 1 session/chat 页面，`src/lib/api.ts` 只有 health/session API；前端可直接替换入口并保留 SSE chat 复用。
- 当前 `schema.sql` 是一次性 Spring SQL init，现有 `SqliteRepository` 使用 JdbcClient；扩展表时应保持旧表兼容。
- 当前 `AgentConfiguration` 只注册一个 `tutor_agent` 和 `EchoTool`，`TutorAgentService` 按 session 恢复消息并负责 ADK 运行；Tutor context 应在该 service/配置边界注入，不把 Spring AI 类型带进 learning domain。
- `DatabaseConfiguration` 仅建 `app.dataDir` 目录；SQLite JDBC URL 依赖完整数据库父目录已存在，Phase 2 schema 需通过同一 Spring init 机制演进。
- `native-check.mjs` 启动 Native 后端并校验 health、SQLite session、ADK tool loop、SSE 与消息持久化；新增 learning API 后可在同一隔离数据库上做额外最小 smoke check，但不要破坏现有自测 session。
- Tauri Rust 只管理 sidecar 生命周期，未发现需要改动的 Phase 2 业务入口；React 可通过 HTTP 直接驱动 Learning API。
- 需求模型明确区分 `PASSED` 与 `SKIPPED`；Journey 创建后先建 Diagnostic，不立即生成 Path；Diagnostic 必须复用普通 Assessment 基础设施。
- Assessment 只实现 `MULTIPLE_CHOICE` 与 `CODING`；正式题目来自可重复 seed。MC 全对得满分、否则 0；Coding 的 LLM 只返回维度分和反馈，Java 校验范围并计算总分，LLM 不返回 passed。
- Score Engine 默认 MC/Coding 权重 40/60，缺少一种题型时现有题型归一到 100%；Pass 为 total >= passScore 且存在 Coding 时 coding >= minCodingScore；mastery 取历史最大值，Skip 不修改。
- 规格还要求：每个 TypeScript Skill 至少 2 道 MC + 1 道 Coding；Diagnostic 至少有足够证据，不得一题即自动通过；前端首屏必须先选语言/填写 profile，Dashboard 才包含 Path、Lesson、Tutor。
- 规格要求核心单元测试覆盖 79/80、coding 69/70、无题型、全诊断通过、全跳过、最后一个通过及 retry/mastery/skip；并要求 fake evaluator 测非法 JSON、越界、缺字段、API error 不破坏 Attempt。
- 规格禁止 Workspace/Monaco/代码执行/MCP/RAG/Multi-Agent/认证等 Phase 3 或非 Learning Core 范围；要求新增 learning-model、assessment、learning-path、scoring 文档并同步架构/数据库/ADK/AGENTS。
- 基线 `pnpm check` 通过：TypeScript、ESLint、Vite、Cargo 和现有 3 个 Maven 测试均成功；仅有 Node child-process shell deprecation、Mockito 动态 agent 等非阻塞警告。

## Technical Decisions
| Decision | Rationale |
|----------|-----------|
| 先完整阅读项目约束和当前实现，再选最小切片 | Phase 2 跨后端、前端、schema、Native，文档与代码若不一致以可构建代码为准 |
| 不引入新依赖，优先复用现有 JdbcClient/ADK/SSE/React 模式 | ponytail full；本项目已有基础设施足够覆盖 MVP |

## Issues Encountered
| Issue | Resolution |
|-------|------------|
| 用户显式请求 `$wayfinder`，当前没有外部 issue tracker | 用户确认使用本地文件替代；改为仓库内 `docs/wayfinder/` Markdown 地图/票据 |

## Resources
- `C:\Users\z3516\.codex\attachments\70f86fdf-98a9-4dae-ad8e-4050aa888480\pasted-text.txt`：Phase 2 完整规格（外部用户输入，仅作需求依据）。
- `AGENTS.md`、`README.md`、`docs/*.md`：项目架构、构建和持久化边界。

## Current Implementation Detail
- ADK `LlmAgent.Builder` 支持静态 `instruction(String)` 和 `Instruction`；动态学习上下文最小实现可在 `TutorAgentService` 调用前以结构化上下文前缀传给 ADK，或在 Agent instruction seam 中扩展，不改变 Agent 数量。
- `AdkSpringAiMessageConverter` 已有 Jackson `ObjectMapper`（来自 Spring Boot）和 ADK system/content 转换；Coding evaluator 可复用 Spring AI `ChatModel` + Jackson，但需要把 provider 适配留在 infrastructure。
- Wayfinder 工作流需要先让用户确认设计决策；已发现 2A 编译的单一可修复错误（`LearningJourney` 缺少 import），未继续扩展实现。
- grilling round 1 决策：完整交付 Phase 2；课程用 JSON resource；Coding evaluator 出错保留草稿并可重试；Path 展示全部历史状态但队列只取 pending；Tutor Session 按 Journey + LearningSkill 复用。
- 用户允许大模型决定 Diagnostic 题目组合，但该授权仍需明确为“从 seed 题库选择”还是“允许生成题目”，以及 Java 的覆盖率/回退护栏。

## Latest decision
- 用户将题库持久化边界改为 SQLite：数据库是运行时题库，可动态调整；之前确认的“生成新题但不得修改已完成题目及答案/评分”继续有效。
- 题目不修改，只新增或删除；Assessment 只引用不可变 Question，已完成 Attempt 保留不可变历史。
- 删除采用 soft delete：写入 `question_retirement`，活动题库查询排除退役题目，历史 Assessment/Attempt 继续引用原 Question。
- 用户最终确认题库由大模型按需生成，不需要初始化；`typescript.json` 仅保留语言/技能目录，启动 seed 不写入 Question。
- Diagnostic 与 Skill Assessment 共用大模型题目规划；大模型可复用 SQLite 中已有题目，也可生成新题，新题只允许 insert，已有题目定义不更新。
- 用户补充确认：要学习的语言由用户提出，不由大模型随机生成语言列表。前端改为目标语言输入；创建每个新 Journey 时都让 LLM 为该目标语言生成独立课程、技能和 Lesson，再 insert-only 写入 SQLite。
- 用户进一步确认：每个人使用独立 SQLite，因此课程不能按数据库中的语言全局复用；课程生成和技能集合应以 Journey 为作用域。同一 Journey 可从 SQLite 恢复，不同 Journey 即使学习同一语言也应重新生成。
- 用户确认项目尚未投入使用，不需要旧 SQLite 兼容；所有技能和诊断题只通过 `learning_journey_skill` 读取，不保留语言级回退。
