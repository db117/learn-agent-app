# Assessment 与 Score Engine 契约

Status: complete  
Parent: [Phase 2 Learning Core map](../phase-2-learning-core-map.md)

## Question

如何让 Diagnostic 与 Skill Assessment 复用同一套题目/attempt 基础设施，同时保持 MC、Coding、Score 和 Pass/Fail 的确定性边界？

## Decision

`AssessmentService` 统一管理 Assessment、Attempt 和 QuestionAttempt。MC 全对得分，
Coding 由 infrastructure evaluator 返回三个受限维度，Java 计算总分；评分/通过规则
不交给 LLM。评估失败先保留 draft，再返回 422。
