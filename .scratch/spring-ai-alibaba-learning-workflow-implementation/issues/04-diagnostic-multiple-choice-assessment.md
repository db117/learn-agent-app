# 04: Diagnostic 与不可变 Multiple Choice 题集

**What to build:** 学习者可以开始 Diagnostic，系统为当前 Journey 固定一套题目并完成 Multiple Choice 评估，结果、题目引用和历史 Attempt 可追溯。

**Blocked by:** 01: SAA 运行时对齐与 Agent/Graph 基础链路; 02: Journey 创建与 LLM LearnUnit 生成/恢复

**Status:** complete

- [x] Diagnostic 可以选择已有题目或按当前 Journey 生成新题，并在创建时固定题集。
- [x] Question 定义只能新增或 soft delete，题干、答案、分值和评分规则不可变。
- [x] Assessment、Attempt 和 QuestionAttempt 保存到 SQLite，历史 Attempt 可读取。
- [x] Multiple Choice 使用 Java 确定性规则评分，不由模型决定分数。
- [x] Diagnostic 结果能按统一评分基础设施返回每个 LearnUnit 的评估结果。
- [x] 前端可以开始 Diagnostic、提交答案并查看结果。

**Evidence:** assessment persistence, insert-only/retirement, deterministic score and diagnostic result tests pass.
