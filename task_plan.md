# Task Plan: Phase 2 Learning Core

## Goal
在现有 Phase 1 项目上交付可运行的 Learning Journey 主流程；用户提出目标语言和目标后，每个新 Journey 独立生成课程、技能内容和题目，SQLite 保存运行时目录、题库和历史状态；不实现 Phase 3 工作区功能。

## Next Step
已完成；Native 检查仅受本机缺少 `native-image` 阻塞。

## Current Phase
Phase 5 - Delivery

## Phases

### Phase 1: Requirements & Discovery
- [x] 阅读用户 Phase 2 说明和仓库约束
- [x] 阅读 README、架构/ADK/GraalVM/数据库/开发文档
- [x] 检查当前后端、前端、schema、测试与构建状态
- [x] 记录可复用代码和差异
- **Status:** complete

### Phase 2: Planning & Structure
- [x] 定义最小可交付领域模型和 API 边界
- [x] 决定静态课程/题目 seed 与 SQLite 持久化边界
- [x] 设计前端状态流和 Native 兼容检查点
- **Status:** complete

### Phase 3: Implementation
- [x] 实现 Learning domain、seed、schema/repository、score/path/progress
- [x] 实现 HTTP API 与 Tutor context 关联
- [x] 实现 React Welcome → Diagnostic → Dashboard/Skill flow
- **Status:** complete

### Phase 3.1: LLM Curriculum Generation Extension
- [x] 移除静态课程资源和启动 Seeder
- [x] 实现语言、技能和 Lesson 的按需 LLM 生成、校验与 SQLite 持久化
- [x] 保留 Java 对路径、评分和状态迁移的确定性控制
- [x] 补充生成、复用、非法响应和完整构建测试
- **Status:** complete

### Phase 3.2: Journey-scoped Curriculum
- [x] 为每个新 Journey 生成独立课程并建立技能关联
- [x] 让路径、诊断、技能评估和前端技能查询只读取当前 Journey 课程
- [x] 删除无用的语言级课程复用和旧库兼容分支
- **Status:** complete

### Phase 4: Testing & Verification
- [x] 添加并运行核心 JVM 单元测试
- [x] 运行 `pnpm check`
- [x] 运行 `pnpm native:check`
- [x] 做最小端到端/启动验证并记录限制
- **Status:** complete with Native environment limitation

### Phase 5: Delivery
- [x] 检查 diff，确保未进入 Phase 3
- [x] 同步必要文档
- [x] 输出用户要求的最终报告
- **Status:** complete

## Decisions Made
| Decision | Rationale |
|----------|-----------|
| 继续现有项目，不重建 | 用户明确要求在 Phase 1 基础上演进 |
| 用户输入目标语言；每个新 Journey 都由 LLM 独立生成 Skill/Lesson 并 insert-only 写入 SQLite；Question 不随应用初始化 | 学习目标由用户决定，Agent 只负责构造该 Journey 的课程和题目；同一 Journey 后续以 SQLite 为唯一来源 |
| 同一 Journey 从 SQLite 恢复；每个新 Journey 都重新生成课程 | 每个用户使用独立数据库，语言级课程复用没有价值；Journey 级关联可避免同语言课程串用 |
| Wayfinder 使用本地 Markdown tracker | 用户明确要求用本地文件替代 issue tracker；地图和票据放在 `docs/wayfinder/` |
| 用户确认完整 Phase 2、JSON curriculum、评估错误保留草稿、Path 保留历史状态、Tutor Session 按 Journey + LearningSkill 复用 | grilling round 1 的明确决策 |
| 诊断允许 LLM 生成新题，但已完成题目的内容、答案、分值和评分规则不可修改 | 用户明确要求新诊断题可由 LLM 生成，同时保护历史题目和已完成结果 |
| Diagnostic 创建时由 LLM 选择/生成题目并持久化；重启和 Retry 复用同一题集 | 保证诊断结果可追溯；新建 Diagnostic 才重新选题 |
| 每个 Skill 至少 1 道选择题 + 1 道 Coding；Java 校验并在 LLM 失败/非法时确定性 fallback | 保留每个 Skill 的概念与编码证据，且不会因模型故障阻塞主流程 |
| 诊断选题复用现有 Spring AI ChatModel，不新增 Agent；TutorAgent 仍是唯一 ADK Agent | 遵守 ADK runtime 与 provider infrastructure 边界 |
| 所有 Question 只存 SQLite；题目内容、答案、分值和评分规则不可更新，只允许新增或从活动题库移除 | 用户要求题库可动态调整，同时保证题目定义稳定 |
| Assessment 只引用不可变 Question；已完成 Attempt 是不可变历史 | 题目不会修改，不需要复制内容快照；历史记录仍需可读 |

## Errors Encountered
| Error | Resolution |
|-------|------------|
