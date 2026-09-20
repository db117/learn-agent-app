# Skills and Memory

## Skill

Skill 描述 Agent 的工作方法，不是课程知识点。

首批核心 Skill：

```text
teaching/socratic-hint
coding/diagnose-error
coding/debug-failing-test
coding/review-code
learning/identify-misconception
transfer/java-to-typescript
```

Socratic Hint 建议三级：

- Level 1：只提示方向。
- Level 2：指出区域、概念和观察点。
- Level 3：提供接近答案的示例，但不直接替用户完成。

未来 Skill 自生成必须经过：

```text
generated → candidate → review → enabled
```

禁止自动生成后直接启用。

## Memory

Memory 保存：长期背景、偏好、常见误区、常犯错误、有效教学方式。

Memory 不保存 completed、mastery 或 PracticeEvidence 等权威事实。

```text
Domain State → SQLite → 权威事实
Agent Memory → Runtime → 长期上下文
```
