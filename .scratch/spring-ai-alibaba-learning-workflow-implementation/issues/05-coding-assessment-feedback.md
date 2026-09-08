# 05: Coding 评估与受限 LLM 反馈

**What to build:** 学习者可以提交 Coding Answer，模型只返回受限 rubric 维度、反馈和问题，Java 校验并计算最终分数；评估异常时草稿保留并可重试。

**Blocked by:** 04: Diagnostic 与不可变 Multiple Choice 题集

**Status:** complete

- [x] Coding Question 使用已固定的 Assessment 题集，并引用不可变 Question。
- [x] LLM 输出只包含允许的 rubric dimensions、feedback 和 issues，非法结构化输出会被拒绝。
- [x] Java 校验 rubric 范围并计算 Coding 分数及最终 Assessment 分数。
- [x] 模型不能返回或决定最终 passed、retry、skip、next LearnUnit 或 Learning Path。
- [x] 模型、网络或校验异常时保留 Attempt draft，学习者可以 Retry。
- [x] Coding 结果、反馈和历史 Attempt 可在前端查看。

**Evidence:** rubric validation, deterministic aggregation, preserved draft and retry tests pass.
