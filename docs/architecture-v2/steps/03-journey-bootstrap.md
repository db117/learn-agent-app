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

## 端到端衔接

Bootstrap 不止负责创建 Journey，还负责把已确认的路径交给学习 Runtime：

```text
创建 Journey
  → learningJourneyId 为空
  → 创建 PLANNING Tutor Session
  → Tutor 生成规划草稿
  → 用户确认规划
  → 按规划草稿的有序段落/阶段物化多个 LearnUnit、LearningPathItem 和 Assessment
  → 保存 LearningJourney，并将 learningJourneyId 挂回 Journey
  → Bootstrap 重新读取 Journey
  → 发现 learningJourneyId 后初始化 Learning Workspace
  → 创建或恢复 LEARNING Tutor Session
  → TutorContextAssembler 加载当前 LearningPathItem 和 current LearnUnit
  → 用户发送首条消息，开始首个 Tutor 学习回合
```

Journey、LearningJourney、LearnUnit、LearningPathItem 和 Assessment 都是 Learning Domain 的
Domain State；PLANNING/LEARNING Session、规划草稿、Tutor 上下文和消息都是 Agent State。Session
只能读取已确认的路径，不能直接写入 Journey、LearningJourney、score、mastery 或 completion。

确认规划时，应用层负责将规划草稿中的有序段落/阶段物化为完整的有序路径，而不是只生成一个占位单元。
生成 LearningJourney 后先保存，再挂回 Journey。当前不引入并发控制或通用跨聚合事务；如果挂接失败，应用层
清理本次新建的 LearningJourney 并保留 Journey 的 `learningJourneyId` 为空，之后允许用户重试。只有挂接成功
后，Bootstrap 才能进入 LEARNING 模式。

进入 LEARNING 模式不等于已经完成首个学习回合：创建/恢复 LEARNING Session 并加载当前 LearnUnit 是 Runtime
进入学习态；用户首条消息才触发 Tutor 的首个学习回合。完成当前单元后，Learning Domain 根据 Assessment
和 PracticeEvidence 决定是否推进到下一个 `PENDING` LearningPathItem；Bootstrap 不自行推进学习状态。

本阶段不实现完整的 LLM 内容生成；只约定确认后的规划如何物化为 LearningJourney、LearnUnit 和 Assessment，
后续步骤负责学习内容与任务的具体运行。本阶段也不把规划草稿直接当作 Domain State。

**DoD：**本地数据库可恢复 Learner、Journey 和当前选择；首次打开不再要求手填 ID；Tutor 可以在无当前
LearnUnit 时进入规划模式；确认后可恢复完整有序的 LearningJourney；Bootstrap 可据此初始化 Workspace、
创建/恢复 LEARNING Tutor Session 并加载首个当前 LearnUnit；UI 不消费 AgentScope raw event；学习完成一个
LearnUnit 后，领域状态能够决定推进到下一个单元或进入 LearningJourney `COMPLETED`。
