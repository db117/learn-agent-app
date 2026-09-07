# Domain 与 SQLite 边界

Status: complete  
Parent: [Phase 2 Learning Core map](../phase-2-learning-core-map.md)

## Question

如何表达 LearningLanguage、LearningSkill、Journey、Learner state、Path、Lesson，并保证重启恢复？

## Decisions

- Language/Skill/Lesson 不使用初始 seed；用户提出目标语言创建新 Journey 时，由 LLM 为该 Journey 独立生成课程内容并写入 SQLite，通过 `learning_journey_skill` 建立技能关联。同一 Journey 后续从 SQLite 恢复，不同 Journey 不共享课程。Question 不随应用初始化，诊断或技能评估需要时由 LLM 生成并写入 SQLite。Journey、Attempt、Path、Learner 状态也使用 SQLite。
- Question 只新增或 soft delete，不更新题干、答案、分值或 rubric；退役记录写入 `question_retirement`。
- Diagnostic 的 LLM 生成题在创建时固定并落库；每个 Skill 至少一题选择题和一题 Coding。
