# 06: Diagnostic 与不可变 Question

**What to build:** 学习者可以完成由 LLM 生成或选择、经 Java 校验的 Diagnostic；诊断结果用于生成 Learning Path，Question
定义创建后固定。

**Blocked by:** 04: Journey/LearnUnit 的 LLM 生成；05: LearningPathItem、TutorContext 与进度展示

**Status:** ready-for-agent

- [ ] Diagnostic 复用 Assessment 基础设施，不建立第二套独立题目系统。
- [ ] 每个 LearnUnit 的 Diagnostic 至少包含两个有效证据项；Java 校验题目结构和题型覆盖。
- [ ] LLM 可以选择或生成 Diagnostic 内容，但 LLM 不直接决定最终分数、通过状态或 Learning Path。
- [ ] 有效 Question 写入 SQLite 后只能新增或 soft delete；题干、选项、答案、分值和评分规则不可更新。
- [ ] Diagnostic 使用创建时固定的 Question 集合，历史诊断结果保持可读；生成或校验失败不产生半完成 Diagnostic。
- [ ] Diagnostic 完成后由 Learning Engine 确定性地更新 LearningPathItem；React 能展示题目、提交结果和初始路径。
