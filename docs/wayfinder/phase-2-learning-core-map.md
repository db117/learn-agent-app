# Wayfinder Map — Phase 2 Learning Core

## Destination

现有 Phase 1 桌面学习 Agent 具备可手工演示、可测试、可 Native 构建的 Learning Journey 主流程，并停在 Phase 2 边界。

## Notes

本地 Markdown tracker；决策票据是一票一决策，代码交付仍由 `task_plan.md` 跟踪。ADK 是唯一 Agent runtime，Learning Engine 控制确定性规则，Phase 3 workspace 能力不在范围内。

## Decisions so far

- [现有 Phase 1 基线与边界](tickets/01-baseline.md)：保留现有 Tauri/React/Spring Boot/ADK/Spring AI/SQLite/SSE 结构，扩展而非重建。
- grilling round 1：完整 Phase 2；JSON curriculum；评估异常保留草稿；Path 保留 COMPLETED/SKIPPED 历史；Tutor Session 按 Journey + LearningSkill 复用。
- grilling round 2：Diagnostic 允许 LLM 生成新题，但不得修改已完成题目及结果；创建时固定并持久化题集；每个 Skill 至少 1 道选择题 + 1 道 Coding；LLM 失败时 Java fallback；复用现有 ChatModel，不新增 Agent。
- 用户最新决策：题库运行时写入 SQLite，数据库支持动态调整；已完成题目及其答案、分值、评分规则和结果仍不可修改。
- 用户进一步确认：所有 Question 都在 SQLite；题目只新增或删除，不更新内容；Assessment 引用不可变题目，已完成 Attempt 保留。
- 删除策略已确认：soft delete，写入 `question_retirement`；活动题库排除退役题目，历史引用保留。
- 最新决策覆盖：应用启动不初始化课程目录或 Question；用户提出目标语言后，仅为该语言由 LLM 生成技能和 Lesson 内容，诊断和技能评估需要题目时再由 LLM 生成或选择；Java 校验后 insert-only 写入 SQLite。
- Journey 作用域决策：每个新 Journey 都由 LLM 独立生成课程并写入 `learning_journey_skill`；同一 Journey 从 SQLite 恢复，不同 Journey 不共享课程；项目尚未投入使用，不保留旧库兼容分支。

## Not yet specified

- Assessment attempt、LLM evaluator 和确定性 Score Engine 的接口与错误边界（错误保留草稿，已落代码）。
- Path progression、Skip/Retry 和 Tutor session/context 的状态联动（已落代码）。
- 前端页面状态机和最终 Native/端到端验证范围（页面已落代码，Native 待最终检查）。

## Out of scope

- Workspace、Monaco、代码执行/test runner、MCP、RAG、multi-agent、账户/认证、云同步、自动更新和 Phase 3。

## Open tickets

- [Domain 与 SQLite 边界](tickets/02-domain-sqlite.md) — complete
- [Assessment 与 Score Engine 契约](tickets/03-assessment-scoring.md) — complete
- [Path Progression 与 Tutor Context](tickets/04-progress-tutor.md) — complete
- [Frontend Flow 与 Native 验证](tickets/05-frontend-verification.md) — complete with Native environment limitation
