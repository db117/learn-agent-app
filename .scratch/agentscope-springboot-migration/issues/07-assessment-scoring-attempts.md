# 07: Assessment 评分与 Attempt 历史

**What to build:** 学习者可以完成 LearnUnit Assessment，系统对选择题确定性评分，对存在的 Coding Question 使用结构化 LLM
rubric 评价，再由 Java 计算最终结果并保留历史 Attempt。

**Blocked by:** 06: Diagnostic 与不可变 Question

**Status:** ready-for-agent

- [ ] Assessment 只引用创建时固定的 Question 集合，Retry 不替换题目、不改变历史题目定义。
- [ ] Multiple Choice 使用固定正确答案和分值确定性评分；LLM 不参与选择题最终判分。
- [ ] Coding Question 仅在 Journey 生成了编码内容时出现；没有编码题时不生成或计算编码分数要求。
- [ ] Coding evaluator 只返回经过结构校验的 rubric 维度和反馈，分数范围由 Java 校验，最终 codingScore 和 passed 由 Java
  计算。
- [ ] 评估提交以一致的事务保存 Question Answer、AssessmentAttempt、分数和必要的学习结果；模型失败、超时或非法 JSON 不产生半完成
  Attempt。
- [ ] React 展示总分、各题型分数、通过阈值、编码阈值和明确的 Passed/Not Yet 结果。
- [ ] 自动化测试覆盖选择题、编码题、choice-only、coding-only、分数边界、非法模型响应和历史 Attempt 保留。
