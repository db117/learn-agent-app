# Assessment

`Assessment` 有 `DIAGNOSTIC` 和 `SKILL` 两种类型，题集通过
`assessment_question` 在创建时固定。`AssessmentAttempt` 记录一次开始/提交，
`QuestionAttempt` 记录每道题的答案、分数、反馈和 Coding 评估 JSON。

诊断或技能评估创建时，LLM 可以从活动题库选题，也可以提出新题；题库为空时直接
生成新题。用户提出目标语言后，语言下的技能和 Lesson 目录才由 LLM 生成并持久化。Java 只接受合法的 MULTIPLE_CHOICE/CODING 题目，已有 Question 必须完全
相等；每个本次评估覆盖的 Skill 至少保留一道选择题和一道 Coding 题。LLM 返回非法
结果或不可用时，仅能从已有 SQLite 题库使用确定性 fallback；全新数据库没有可回退
题目时，评估创建会失败并等待 LLM 可用。新题插入 SQLite 后成为不可变 Question。

选择题是全对得满分，否则 0。Coding 通过
`CodingAnswerEvaluator` 评分：`correctness` 0..60、`languageUsage` 0..20、
`clarity` 0..20，Java 计算总分，不接受模型直接返回 passed。

未完成 Attempt 可以继续保存答案；Retry 新建 Attempt，但技能评估沿用同一 Assessment
和题集。Coding evaluator 失败时先保存 draft，再返回 422；不会把失败伪装成 0 分或
完成 Attempt。
