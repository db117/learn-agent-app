# CONTEXT.md

## 产品

Agentic Programming Learning Environment（智能编程学习环境）。

## 核心模式

- Learn：学习一个编程概念。
- Practice：编写并验证真实代码。
- Project：完成一个真实项目。

## 核心概念

- Learner：本地当前用户的背景能力档案；背景描述由用户确认，是 Tutor 使用背景的唯一事实源。
- Journey：Learner 要学习或完成的目标描述；一个 Learner 可以拥有多个 Journey，同时只有一个当前 Journey。
- LearnUnit：学习者需要学会什么；具体内容由大模型为单个 LearningJourney 生成，以 Journey 内稳定 `code` 标识，不跨 Journey
  共享，创建后不可原地修改。
- PracticeTask：学习者需要构建或修复什么；一个 LearnUnit 可以有多个 PracticeTask，一个通过验证的任务即可满足练习条件。
- Skill：Agent 在重复场景中应如何工作。
- LanguagePack：面向一种编程语言的产品级支持包。
- Domain State：权威的学习事实。
- Agent State：Runtime 的上下文、session、memory、plan 和 tool 状态。
- LearningJourney：某个 Journey 唯一的有序学习路径；Journey 创建后可以先没有 LearningJourney，用户通过 Tutor 规划并确认后才
  持久化路径。LearningJourney 拥有自己的内容快照和学习进度。
- LearningPathItem：Journey 中针对一个 LearnUnit 的进度事实，是该进度的唯一权威来源；从其他当前项返回时，原 `CURRENT` 转为
  `SKIPPED`，目标项转为 `CURRENT`；`SKIPPED` 可以再次激活后完成。
- Chapter：Journey 内 LearnUnit 的有序分组，不跨 Journey 共享。
- PracticeAttempt：一次独立的练习提交记录。
- PracticeEvidence：练习提交产生的客观、可验证证据，包含编译、测试、Lint、运行结果、提交文件和验证时间；是否通过由
  `verificationPolicy` 确定性判定。
- VerificationPolicy：PracticeTask 要求的固定检查项，包括编译、测试、Lint 和运行。
- Project：与一个 LearningJourney 一一对应的真实项目，拥有必需且唯一的 `journeyId`。
- ProjectMilestone：归属于一个 Project 的领域进度节点，不等同于 Agent Plan。
- Mastery：由通过的 PracticeEvidence 按确定性规则计算出的掌握状态；按 `journeyId + learnUnitId` 隔离。
- Entity ID：由 SQLite 持久化层自增产生，不采用 UUID。
- LearningWorkspace：Practice 模式的代码工作区。
- ProjectWorkspace：Project 模式的代码工作区。
- ProjectEvidence：证明 ProjectMilestone 已完成的客观、不可变证据，包含 Workspace/Artifact 引用、验证摘要、通过标记和验证时间。
- TutorAgent：唯一面向用户的主编排 Agent。

PracticeAttempt 与 PracticeEvidence 是不可变历史记录；重试或重新提交会形成新的历史记录，不覆盖既有记录。
LearnUnit 和 Chapter 作为 Journey 内内容快照创建后不可原地修改；重新生成会产生新的内容对象。
LearningJourney 在全部 LearningPathItem 完成或跳过时可以完成；显式恢复跳过项时可以重新进入 `ACTIVE`。Journey 自身只管理目标的
`ACTIVE/ARCHIVED` 生命周期，不复制 LearningJourney 的完成事实。
Practice 只能针对 `CURRENT` 的 LearningPathItem 验证；`SKIPPED` 必须先恢复，`COMPLETED` 拒绝新验证。
没有前置条件的 LearnUnit 优先进入路径候选；其余候选按前置关系拓扑排序，并用 `sequence/code` 稳定排序。
Practice 的 VerificationPolicy 只声明固定检查项；所有必需检查通过后 PracticeEvidence 才通过。
ProjectMilestone 只有在存在通过的 ProjectEvidence 时才能完成；全部 Milestone 完成后 Project 才能完成。
自增 Entity ID 在保存时由 SQLite 产生；新对象在持久化前不要求拥有稳定 ID。

详细架构和路线图位于 `docs/architecture-v2/`。
