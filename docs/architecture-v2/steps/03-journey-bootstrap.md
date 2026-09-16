# Step 3.5 — Learner + Journey Bootstrap

## 执行原则

- Learner 与 Journey 是 Learning Domain 的事实，不写入 Agent State。
- Domain 不依赖 AgentScope、LLM 或 HTTP；模型调用只在应用编排边界发生。
- 本地应用只有一个用户；UI 不要求用户输入 Entity ID。
- Journey 先保存用户目标；路径规划对话只产生草稿，用户确认后才持久化 LearningJourney。

## 目标与范围

实现首次打开引导：

1. 设置 Learner 的背景能力描述。
2. 创建 Journey 或选择已有 ACTIVE Journey。
3. Journey 无 LearningJourney 时，以 PLANNING 模式进入 Tutor。
4. 规划草稿保存在 AgentScope Session 状态；正式学习仍只消费已确认的 LearningJourney。

本阶段不实现 LearnUnit 详细内容、PracticeTask、Assessment 的大模型生成；这些由后续 Domain 按需负责。
本阶段也不伪造正式 LearningJourney；规划会话只保存 Agent State 草稿，路径确认与原子持久化留给 LearningJourney 生成编排。

**DoD：**本地数据库可恢复 Learner、Journey 和当前选择；首次打开不再要求手填 ID；Tutor 可以在无当前 LearnUnit 时进入规划模式；UI
不消费
AgentScope raw event。
