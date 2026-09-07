# Path Progression 与 Tutor Context

Status: complete  
Parent: [Phase 2 Learning Core map](../phase-2-learning-core-map.md)

## Question

如何保证 PASSED/SKIPPED、Retry、Next Skill 和每个 Journey + LearningSkill 的 Tutor Session 一致，并把 LearnerProfile/current skill/weak points 传给唯一 TutorAgent？

## Decision

`ProgressService` 只推进确定性状态，Path 保留 COMPLETED/SKIPPED 历史，mastery 取历史
最大值；每个 Journey + LearningSkill 复用一个 `tutor_session`。`TutorContextService`
通过 ADK 动态 instruction 提供 profile、当前技能和弱点。
