# Assessment

`Assessment` 有 `DIAGNOSTIC` 和 `LEARN_UNIT` 两种类型，题集通过 `assessment_question`
在创建时固定。`AssessmentAttempt` 记录一次开始/提交，`QuestionAttempt` 记录每道题的
答案、分数、反馈和 Coding 评估 JSON。

诊断或 LearnUnit 评估创建时，LLM 可以从活动题库选题，也可以提出新题；题库为空时直接
生成新题。Java 只接受合法的 `MULTIPLE_CHOICE`/`CODING` 题目，已有 Question 必须完全
相等；每个本次评估覆盖的 LearnUnit 至少保留一道选择题和一道 Coding 题。非法结构化
响应会被拒绝；模型不可用时直接报错，不从已有 SQLite 题库回退，也不保存部分生成结果。

选择题由 Java 确定性评分。Coding 通过 `CodingAnswerEvaluator` 只返回受限 rubric
维度、feedback 和 issues，Java 校验范围并计算最终分数，不接受模型直接返回 passed。

未完成 Attempt 可以继续保存答案；Retry 新建 Attempt，但 LearnUnit 评估沿用同一
Assessment 和题集。Coding evaluator 失败时先保存 draft，再返回 422；不会把失败伪装
成 0 分或完成 Attempt。
