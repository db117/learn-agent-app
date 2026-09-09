# 06: Diagnostic 与不可变 Question

**What to build:** 学习者可以完成由 LLM 生成或选择、经 Java 校验的 Diagnostic；诊断结果用于生成 Learning Path，Question
定义创建后固定。

**Blocked by:** 04: Journey/LearnUnit 的 LLM 生成；05: LearningPathItem、TutorContext 与进度展示

**Status:** resolved

- [x] Diagnostic 复用 Assessment 基础设施，不建立第二套独立题目系统。
- [x] 每个 LearnUnit 的 Diagnostic 至少包含两个有效证据项；Java 校验题目结构和题型覆盖。
- [x] LLM 可以选择或生成 Diagnostic 内容，但 LLM 不直接决定最终分数、通过状态或 Learning Path。
- [x] 有效 Question 写入 SQLite 后只能新增或 soft delete；题干、选项、答案、分值和评分规则不可更新。
- [x] Diagnostic 使用创建时固定的 Question 集合，历史诊断结果保持可读；生成或校验失败不产生半完成 Diagnostic。
- [x] Diagnostic 完成后由 Learning Engine 确定性地更新 LearningPathItem；React 能展示题目、提交结果和初始路径。

## Answer

- Diagnostic 继续复用 `Assessment`、`AssessmentAttempt`、`QuestionAttempt` 和固定的 `assessment_question` 关联；题目不足时才调用 LLM 补充，Java 在任何持久化前校验题目结构、题型覆盖、证据数量和 Journey 归属。
- 每个诊断 LearnUnit 至少保留两个有效且 `diagnosticEligible` 的 Question；有 coding 学习目标时必须包含 coding 题。LLM 失败、重复题目、修改既有题目或覆盖不完整都会直接失败，不创建半完成 Assessment。
- SQLite 既有 insert-only/soft-delete 与固定题集历史读取路径保持不变；提交时由 Java 评分并调用 `ProgressService` 更新诊断结果和 Learning Path。现有 React Assessment 页面已展示题目、结果和路径，因此无需额外前端改动。
- 验证：`pnpm backend:test` 通过 56 个测试；最终 `pnpm check` 通过。
