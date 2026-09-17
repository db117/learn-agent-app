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

`VerifyResponse` 只包含验证摘要、相对提交文件和 Learning Domain 推进结果；验证
成功时才会写入 `PracticeEvidence` 并推动领域状态，失败时不会推进领域状态。当前
没有把这条 REST 链路复制到 Tutor Session 的 SSE 总线：这样既没有稳定的 SSE 消费者，
也会产生重复状态来源。

因此 UI 对 Practice 完成状态消费 `VerifyResponse`，对 Tutor 运行活动消费安全的
`TutorEvent`。只有在出现明确的跨组件 SSE 消费场景后，才将 `practice.verified`
加入 `TutorEventType`，并通过同一安全投影提供；不得直接暴露 AgentScope raw event、
宿主路径、完整日志、提示词、答案、secret 或私有推理。

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
  project/
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
