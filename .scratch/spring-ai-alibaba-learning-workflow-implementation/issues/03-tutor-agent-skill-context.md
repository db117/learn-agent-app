# 03: TutorAgent 与 Agent Skill 的 LearnUnit 上下文对话

**What to build:** 学习者在 Journey 中打开当前 LearnUnit 后，可以获得带有个人资料、目标语言、掌握度和薄弱点上下文的 TutorAgent 教学对话，并通过现有 SSE 接口流式查看结果。

**Blocked by:** 01: SAA 运行时对齐与 Agent/Graph 基础链路; 02: Journey 创建与 LLM LearnUnit 生成/恢复

**Status:** complete

- [x] TutorAgent 每次调用都能获得当前 Journey、LearnerProfile 和 LearnUnit 上下文。
- [x] Agent 可以发现并使用 Spring AI Alibaba Skill 提供的 Agent capability。
- [x] Skill 不承载 LearnUnit 内容、学习进度、Journey 状态或用户分数。
- [x] Tutor 回复通过框架无关的 SSE 事件流返回，并包含正常完成和错误结束语义。
- [x] Tutor Session 按 Journey 和当前 LearnUnit 持久化并可恢复。
- [x] React Tutor 页面可以展示流式回复，且不依赖 Spring AI Alibaba 内部类型。

**Evidence:** `ClasspathSkillRegistry` discovery/read, dynamic Tutor context, framework-neutral event projection and React typecheck pass.
