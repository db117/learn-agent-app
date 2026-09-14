# 桌面编程学习 Agent

本文档定义桌面学习应用中的学习领域，以及 Agent runtime 使用的核心概念。

## 术语定义

**Skill**：
向 Agent 暴露的 Agent framework 能力，遵循框架规定的 Skill 元数据和加载模型。 _避免使用_：LearnUnit、lesson

**Chapter**：
Journey 范围内由多个相关 LearnUnit 组成的有序学习单元组，包含学习目标和综合评估。其进度由各个
LearnUnit 及综合评估共同决定；它不是第二条 learning path。 _避免使用_：global course、module

**LearnUnit**：
Journey 范围内可独立验证的最小能力单元。它包含面向 Journey 目标语言和学习目标的讲解、示例、引导练习、
独立检查、目标、前置条件和评估策略；Journey 进度由 `LearningPathItem` 表示。 _避免使用_：Skill、lesson、agent
capability、MicroLearnUnit、LearnerLearnUnit、learner-state record

**LearningPathItem**：
Journey 专属的 LearnUnit 有序节点，其状态是权威进度状态：pending、current、completed 或 skipped。
它还记录学习者在该 LearnUnit 中的当前阶段、已跳过阶段和 review debt；它是路径/进度记录，不是另一种
learning-unit 概念。 _避免使用_：LearnerLearnUnit、LearningSkill、course content

**Learning phase**：
LearnUnit 教学循环中的一个阶段：讲解、示例、引导练习或独立检查。学习者可以跳过某个阶段，但跳过记录
仍属于学习历史；跳过独立检查会阻止 LearnUnit 完成。 _避免使用_：workflow step、UI tab

**Independent check**：
用于验证学习者是否具备某项 LearnUnit 能力的正式评估。只有确定性的通过结果才能完成 LearnUnit；引导练习
用于准备学习者，但不能单独证明掌握。 _避免使用_：practice question、tutor feedback

**Question role**：
正式题目的不可变用途和归属边界：diagnostic 题目支持 Journey 入口，independent 题目属于一个 LearnUnit，
综合题属于一个 Chapter。题目被 Assessment 引用后，其角色和归属就不能再修改。 _避免使用_：generated prompt、answer
draft、evaluator state

**Chapter synthesis**：
在 Chapter 中的 LearnUnit 被尝试后，用于检查这些能力组合的评估。综合评估通过后，不会清除仍未解决或未通过
的 LearnUnit。 _避免使用_：final exam、course exam

**Review debt**：
由错误或独立检查失败产生的未解决学习需求。相关 LearnUnit 独立通过后，该需求会被清除；它不是基于日历的
复习计划。 _避免使用_：spaced repetition queue、review calendar

**TutorContext**：
从 LearningJourney、当前 LearnUnit、LearningPathItem 和评估历史派生的只读 runtime context。它告诉
`TutorAgent` 学习者正在学习什么、处于 Journey 的哪个位置、已经学会什么以及下一步应该做什么。它不是
AgentScope Skill，也不是独立的学习实体。 _避免使用_：Skill、LearnerLearnUnit、final score authority

**TutorSession**：
以 Journey 和 LearnUnit 为范围的教学对话，为一个学习单元保留连续性，但不成为学习进度或评估事实的来源。
重新进入同一个 LearnUnit 时复用该会话；删除会话不会删除学习数据。 _避免使用_：Learning Journey、AgentScope Skill、Learning
State

**AgentState**：
由 Agent framework 持有的 runtime state，用于恢复 TutorSession 的对话、harness 和工具执行上下文。它与持久化
的 Learning State 分离，绝不决定学习结果。 _避免使用_：LearnUnit progress、Assessment result、TutorContext authority

**TutorEvent**：
由项目拥有、与 framework 无关的事件，从 TutorAgent runtime 活动投影而来，供桌面 UI 使用。它可以展示助手
输出、安全的思考摘要、Skill 加载状态和工具进度，但不能暴露原始 AgentScope 事件或内部提示词。 _避免使用_：AgentEvent、raw
reasoning、Skill-to-LearnUnit relationship

**Learning Journey**：
学习者围绕目标语言和学习目标进行的持久化学习进程，包括自身的 Chapter、LearnUnit、当前状态和评估历史。
不同 Journey 不共享课程目录。 _避免使用_：Agent session、global course

**TutorAgent**：
负责教学互动和讲解的唯一 Agent；它不决定确定性的学习进度，也不决定最终评估结果。 _避免使用_：multi-agent、Learning Engine

**Spring Boot WebFlux Application**：
桌面后端的目标应用边界。它负责 HTTP、SSE、SQLite 访问和 Learning Engine；Rust/Tauri 负责桌面壳以及后端
进程生命周期。它不是 Agent runtime，也不通过模型决定学习结果。 _避免使用_：AgentScope Skill、TutorAgent、Learning Engine

**AgentScope Runtime**：
应用中唯一的 Agent runtime。它负责 `HarnessAgent`、`Skill`、`AgentState`、工具执行和 Agent 事件；Spring Boot
WebFlux 负责 HTTP、SSE、SQLite 和 Learning Engine。 _避免使用_：Learning Engine authority、HTTP DTOs、framework-specific
events

**Native Gate**：
Native 可执行路径的未来验证检查点。Native 不在当前迁移范围内，也不决定 macOS arm64 上 Spring Boot WebFlux
JVM 链路是否验收通过。 _避免使用_：current migration blocker、JVM chain acceptance、current Definition of Done

**Model provider configuration**：
学习者在应用范围内选择的模型 provider 连接配置，用于 TutorAgent 和 LLM 辅助学习操作。它标识 provider 协议、
endpoint、model 和 credential，并独立于任何 Learning Journey。 _避免使用_：Skill、LearnUnit、TutorContext、Journey-scoped model
choice
