# Events and UI

## Event Projection

UI 永远不消费 AgentScope Raw Event：

```text
AgentScope Event
→ TutorEventMapper
→ TutorEvent
→ SSE
→ React
```

建议 TutorEvent：

```text
message.delta
skill.loaded
tool.started
tool.completed
tool.failed
plan.created
plan.updated
journey.created
journey.selected
journey.planning
permission.requested
permission.resolved
subagent.started
subagent.completed
workspace.changed
practice.verified
error
```

`practice.verified` 是可选的事件类型建议，不是当前 Step 5 的 Tutor SSE
契约。Practice 当前是独立的 REST action：

```text
POST /api/journeys/{journeyId}/practice/verify
→ VerifyResponse
→ React PracticeWorkspace
```

Practice UI 在一个练习面板中展示当前任务、文件和 Tutor 对话。Monaco 保持可编辑；学习者可以新建和保存文件。
面板不提供手动“编译”或“测试”按钮。学习者提交练习时，应用自动运行固定编译和测试检查：

```text
学习者提交 → 编译/测试 → VerifyResponse 与 PracticeEvidence → Tutor 诊断和评估
→ Tutor 判断 READY/CONTINUE → 学习者确认 → Learning Domain 更新学习进度
```

`VerifyResponse` 只包含验证摘要、相对提交文件和新建的 `PracticeEvidence`；验证通过时记录客观编译/测试结果，
失败时记录失败结果。验证响应本身不判定学习掌握，也不推进 Learning Domain。当前
没有把这条 REST 链路复制到 Tutor Session 的 SSE 总线：这样既没有稳定的 SSE 消费者，
也会产生重复状态来源。

因此 UI 对 Practice 检查结果消费 `VerifyResponse`，对 Tutor 运行活动消费安全的
`TutorEvent`。只有在出现明确的跨组件 SSE 消费场景后，才将 `practice.verified`
加入 `TutorEventType`，并通过同一安全投影提供；不得直接暴露 AgentScope raw event、
宿主路径、完整日志、提示词、答案、secret 或私有推理。

规划确认只保存 LearnUnit 大纲；进入 Learn Mode 时，`learning-content-generation` Skill 根据当前上下文生成
Concept、Example、Practice，并调用 `save_learning_content` 工具保存 Domain 内容快照。`GET /api/journeys/{journeyId}/learning`
和后续 Practice REST 只读取该快照。规划草稿由 `learning-outline-generation` Skill 直接生成，不再通过 Java Generator 工具调用模型。

## 不展示 Chain-of-Thought

UI 可以展示：

```text
✓ Loaded diagnose-error
✓ Read src/main.ts
✓ Ran TypeScript compiler
✗ Found 2 compiler errors
▶ DebugAgent investigating
```

但不展示模型私有推理。

## 前端结构

```text
src/
app/
features/
  journey/
  learn/
  practice/
  workspace/
    editor/
    files/
    terminal/
  agent/
    chat/
    activity/
    plan/
    permission/
    subagent/
shared/
  api/
  ui/
  types/
```

Agent Activity 是一等产品视图。
