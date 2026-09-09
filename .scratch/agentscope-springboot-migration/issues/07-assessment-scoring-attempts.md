# 07: Assessment 评分与 Attempt 历史

**What to build:** 学习者可以完成 LearnUnit Assessment，系统对选择题确定性评分，对存在的 Coding Question 使用结构化 LLM
rubric 评价，再由 Java 计算最终结果并保留历史 Attempt。

**Blocked by:** 06: Diagnostic 与不可变 Question

**Status:** resolved

- [x] Assessment 只引用创建时固定的 Question 集合，Retry 不替换题目、不改变历史题目定义。
- [x] Multiple Choice 使用固定正确答案和分值确定性评分；LLM 不参与选择题最终判分。
- [x] Coding Question 仅在 Journey 生成了编码内容时出现；没有编码题时不生成或计算编码分数要求。
- [x] Coding evaluator 只返回经过结构校验的 rubric 维度和反馈，分数范围由 Java 校验，最终 codingScore 和 passed 由 Java
  计算。
- [x] 评估提交以一致的事务保存 Question Answer、AssessmentAttempt、分数和必要的学习结果；模型失败、超时或非法 JSON 不产生半完成
  Attempt。
- [x] React 展示总分、各题型分数、通过阈值、编码阈值和明确的 Passed/Not Yet 结果。
- [x] 自动化测试覆盖选择题、编码题、choice-only、coding-only、分数边界、非法模型响应和历史 Attempt 保留。

## Answer

- `AssessmentService` 沿用固定的 `assessment_question` 题集；Retry 只新增 Attempt，历史 Attempt 和 Question 定义不更新。LearnUnit 评估允许 choice-only 或 coding-only，诊断仍遵循 06 的证据覆盖规则。
- `MultipleChoiceEvaluator` 和 `AssessmentScoreEngine` 使用 Java 固定答案、题目分值和 40%/60% 混合规则；单一题型按自身百分比归一化。未评分题目不能被静默折算为 0 分。
- `LlmCodingAnswerEvaluator` 只接受受限 rubric 维度、feedback 和 issues；Java 校验维度/反馈结构、题目满分边界，并由 `LearnUnitPassPolicy` 决定 codingScore 和 passed。模型失败、超时或非法响应只保留可重试 draft，不写最终 Attempt 或完成状态。
- Assessment 结果 DTO 补充通过阈值和 Coding 阈值，React 展示总分、存在的题型分数、阈值及 `Passed`/`Not Yet`。
- 验证：`pnpm check` 通过，后端 67 个测试全部通过；`git diff --check` 通过。Native 检查按当前 AGENTS.md 范围未运行。
