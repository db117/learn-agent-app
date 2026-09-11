# ADR 0002: 采用 Journey → Chapter → LearnUnit 的渐进式微学习结构

Status: accepted

Date: 2026-09-11

每个 Learning Journey 独立拥有有序的 Chapter 和 LearnUnit；LearnUnit 是可独立验证的最小能力，不再额外引入
MicroLearnUnit 概念。内容和固定的 independent check 在 learner 首次进入时按需生成并持久化，学习过程按阶段推进，Chapter
在其 LearnUnit 之后进行 synthesis；这样可以把课程拆成可恢复、可验证的短学习闭环，同时避免预先生成完整课程和让 LLM
直接决定学习结果。Learning Engine 仍负责阶段迁移、跳过、评分、通过、复习债务和路径状态。

## Consequences

- Chapter completion 必须由 LearnUnit 的确定性进度与 Chapter synthesis 共同约束。
- 独立检查题目一旦生成就属于该 LearnUnit 的固定 Assessment，历史 Attempt 保持可读。
- UI 和 API 需要表达当前 LearnUnit 的阶段、跳过记录和 review debt，而不是只展示整章长课文。
- 不兼容旧的 Journey 课程结构；发现旧 schema 时要求重新初始化。
