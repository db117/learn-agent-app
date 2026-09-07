# Progress Log

## Session: 2026-09-07

### Current Status
- **Phase:** 5 - Delivery
- **Started:** 2026-09-07

### Actions Taken
- 读取并理解用户提供的 Phase 2 规格概要。
- 检查仓库状态：现有代码未见 Phase 2 learning domain；工作区仅有用户已有 `src-tauri/icons/icon.png` diff。
- 检查技能：wayfinder 文件存在但未注册；读取 ponytail 与 planning-with-files 说明；用户随后确认使用本地文件 tracker。
- 初始化本任务的 `task_plan.md`、`findings.md`、`progress.md`。
- 将 Phase 2 目标、阶段和当前架构决策写入 `task_plan.md`。
- 完整阅读 `AGENTS.md`、`README.md`、`docs/architecture.md`、`docs/adk.md`、`docs/graalvm.md`、`docs/database.md`、`docs/development.md`。
- 阅读 `pom.xml`、`schema.sql`、`application.yml`、前端 API/入口/样式和后端主要源文件，确认 Phase 1 的复用边界。
- 阅读数据库配置、Native hints、现有 JVM 测试、Native smoke 脚本、Tauri 生命周期和 TypeScript 配置。
- 阅读 Phase 2 规格前半段（领域、题型、评估、评分和通过规则），确认必须复用普通 Assessment 流程并保持确定性业务规则在 Java。
- 阅读 Phase 2 规格后半段（Progress、Tutor context、API、schema、UI、seed、测试、Native、执行顺序和 DoD）。
- 运行基线 `pnpm check`：成功（现有 Maven 3 tests passed）。
- Phase 1 discovery complete; begin backend domain/API design.
- 用户确认 Wayfinder 使用本地 Markdown tracker；准备在 `docs/wayfinder/` 建立地图与开放票据。
- 已创建 `docs/wayfinder/phase-2-learning-core-map.md` 和 4 个开放决策票据，基线票据已关闭。
- 用户指出 Wayfinder 应先进行 grilling；已暂停后续实现，先等待设计决策。
- grilling round 1 已回答：Q1/Q2/Q4/Q5/Q6 接受建议；Q3 授权 LLM 决定 Diagnostic 题目组合，正在澄清其边界与护栏。
- grilling round 2 已完成：允许 LLM 生成新 Diagnostic 题，但保护已完成题目及结果不可变；创建时固定题集；每个 Skill 至少 1 道选择题 + 1 道 Coding；Java fallback；复用现有 ChatModel，不新增 Agent。
- 规划决策已锁定，开始收敛 2A 后端草稿。
- 用户将题库改为 SQLite 运行时数据并要求支持动态调整；已暂停实现，等待 seed、写入方和快照边界确认。
- 用户进一步确认：所有题目运行时来自数据库，题目只新增或删除，不修改；删除采用 soft delete，保留被历史 Attempt 引用的题目。
- 修复 LLM 评估器/诊断规划器对 Jackson 2 `ObjectMapper` 的错误 Spring 注入，改复用 ADK 的 `JsonBaseModel` mapper。
- 后端 assessment state 增加当前 open attempt 的 questionAttempts，支持前端重启后恢复未完成答案。
- 前端 API 类型与请求层改为 Learning API；入口改成 Welcome/profile → Diagnostic → Result → Learning path/skill → Tutor SSE 流程。
- 新库启动验证通过：健康检查、TypeScript language seed、Journey 创建、诊断题集创建（12 题）、Assessment 启动及答案写入/恢复返回均正常。
- 文档同步完成：新增 learning model/assessment/path/scoring 说明，并更新架构、数据库、ADK、开发命令和 AGENTS.md 的 Phase 2 边界。
- 代码复核修复 Coding 评估异常的事务回滚边界：`AssessmentEvaluationException` 不回滚，draft QuestionAttempt 可保留并重试；前端最后一题避免重复提交。
- 为 Native Image 注册 `curriculum/.*` 资源模式，确保 Native 启动时能加载课程初始 seed。
- 收口路径状态约束：`startSkill`/`skipSkill` 仅允许操作当前 `CURRENT` 项，Tutor 弱点上下文排除已答对的题目反馈。
- 补充 ProgressService、AssessmentService retry 和 SQLite 集成覆盖，验证 Skip、Retry、Mastery、题目 soft delete 与历史 Assessment 引用。
- 开始中文注释整理：为 LearningLanguage、LearningSkill、LearningJourney、LearnerProfile、LearnerSkill、LearningLesson、LearningPathItem 和 AssessmentScore 增加 record 字段说明。
- 继续为 Assessment/Question 相关 record 与 CodingAnswerEvaluator、DiagnosticQuestionPlanner 接口增加中文职责、字段和边界说明。
- 为 AssessmentService 内部结果、Score 输入、题库关联和 LearningController 请求/响应 record 增加中文 API 契约注释。
- 为 schema.sql 的 Phase 1/Phase 2 表、软删除边界和查询索引补充中文数据库注释。
- 为 Phase 1 的 Session/Message/Event/Run 记录、配置 record、数据库 Repository 和 HTTP API 增加中文职责与字段注释。
- 将 LLM/ADK 基础设施中遗留的英文类注释翻译为中文，并补充 Learning API 各端点及异常响应说明。
- 注释整理后重新运行 `pnpm check` 和 `git diff --check`：构建、Lint、Cargo、17 项 JVM 测试均通过；仅有 Git 的 LF/CRLF 提示。
- 用户确认题库由大模型按需生成，不需要随应用初始化；开始移除 Question seed，并改造 Skill Assessment 的按需生成链路。
- 已移除 typescript.json 中的 Question seed 和 Repository 的题目初始化循环；保留语言/技能目录 seed。
- AssessmentService 现在让 Diagnostic 与 Skill Assessment 共用 LLM 题目规划，空题库也可生成并 insert-only 写入 SQLite；同步更新 AGENTS、架构、数据库、评估和 Wayfinder 文档。
- 新增“空题库时 Skill Assessment 由 LLM 生成题目”的单元覆盖；`pnpm backend:test` 18 项通过，`pnpm check` 全部通过。
- 最终验收确认 `typescript.json` 仅含 1 个语言和 6 个技能、没有 `questions` 属性；`git diff --check` 通过（仅有 LF/CRLF 提示）。
- 同步修正规划记录中的测试数量，并记录“题库由大模型按需生成、不随应用初始化”的最终决策。
- 按本次资源变更重新运行 `pnpm native:check`；仍在 Native 编译前因本机 Eclipse Adoptium JDK 缺少 `native-image` 阻塞，代码未发现新的 Native 错误。
- 用户进一步要求语言、技能和 Lesson 内容也由大模型生成；已移除 `typescript.json`、`CurriculumCatalog` 和启动 `CurriculumSeeder`。
- 新增 `CurriculumGenerator`/`CurriculumService` 与 `LlmCurriculumGenerator`：用户提出新语言时按需生成、校验并 insert-only 写入 SQLite，后续只读数据库；路径排序和评分仍由 Java 确定性规则负责。
- 补充空数据库目录生成、目录复用、非法目录和 LLM JSON 解析测试；`pnpm backend:test` 23 项通过。
- 前端去除固定 TypeScript 默认文案，改为跟随模型生成的所选语言；`pnpm check` 全部通过（Maven 23 项）。
- 按动态课程改动重新运行 `pnpm native:check`；仍在 Native 编译前因本机 Eclipse Adoptium JDK 缺少 `native-image` 阻塞。
- 最终检查无残留 `CurriculumCatalog`、`CurriculumSeeder`、`typescript.json` 或 `repository.seed(...)` 引用；工作区差异检查通过。

### Test Results
| Test | Expected | Actual | Status |
|------|----------|--------|--------|
| `pnpm backend:test` | Backend compile and all JVM tests pass | 23 tests passed, 0 failures | PASS |
| `pnpm check` | TypeScript, ESLint, Vite, Cargo and Maven checks pass | All stages passed; Maven 23 tests passed | PASS |
| `pnpm native:check` | Native compile, startup, SQLite and ADK/SSE smoke pass | Executed; blocked before compile because `native-image` is absent from the Eclipse Adoptium JDK | ENVIRONMENT BLOCKED |

### Session: 2026-09-08

- 用户指出学习语言应由用户提出；已将语言生成改为按用户输入的单一目标语言触发，目录查询只读 SQLite，前端改为自由输入语言名称或编码。
- 后端 23 项测试通过；完整 `pnpm check` 通过，确认前端输入流程和后端目标语言生成改动。
- `pnpm native:check` 已重跑；Native AOT 在本机 Eclipse Adoptium JDK 缺少 `native-image` 处阻塞，未进入 Native 编译。

### Session: 2026-09-08 (Journey-scoped curriculum)

- 用户指出每个用户使用独立 SQLite，语言级课程复用没有跨用户价值；决定改为每个新 Journey 独立生成课程，同一 Journey 只从 SQLite 恢复。
- 发现当前技能按语言全局查询会导致不同 Journey 串用技能和题目；采用新增 `learning_journey_skill` 关联表隔离生成课程，同时为旧 Journey 保留无关联时的兼容读取。
- 已将 Journey 创建流程改为先按用户目标调用 LLM 生成课程，再写入语言、Journey、画像和 Journey-技能关联；同一语言的新 Journey 不再复用已有课程。
- 用户确认项目尚未投入使用，因此移除旧 Journey 兼容回退；技能、路径和诊断题全部按 Journey 关联读取。
- 删除未再使用的语言级技能和全局诊断题查询；新增的集成测试验证两个相同语言 Journey 的技能集合相互隔离。
- `pnpm backend:test` 和完整 `pnpm check` 均通过，共 23 项 JVM 测试；`pnpm native:check` 仍因本机 JDK 缺少 `native-image` 阻塞在 AOT 编译阶段。
- 排查 IntelliJ 的 pnpm 提示：系统 PATH 没有真实 pnpm，只能看到 Codex 临时 shim；已在 `package.json` 声明 `pnpm@11.19.0`
  ，并验证 `corepack pnpm --version` 返回 11.19.0。

### Errors
| Error | Resolution |
|-------|------------|
| wayfinder 无 tracker/tool 支持 | 采用仓库文件规划，未修改 issue tracker |
| 规划文件补丁首次上下文不匹配 | 重新读取实际模板内容后分文件精确更新 |
| 2A 首次 Maven 编译失败：`LearningRepository` 漏导入 `LearningJourney` | 暂停修复，先完成用户要求的 grilling；之后做一次精确导入修复 |
| 后端 Spring context 启动失败：`ObjectMapper` 没有 Bean | LLM JSON 组件改用 ADK `JsonBaseModel.getMapper()`，去掉构造器注入 |
| `pnpm native:check` 无法开始 Native 编译 | 当前机器仅有 Eclipse Adoptium JDK 21，未安装 GraalVM/native-image；代码门禁已记录为环境阻塞 |
| Progress/assessment coverage first compile error | New tests compile and run | Fixed missing `assertTrue` import and Mockito matcher usage; rerun passed 17/17 | RESOLVED |
