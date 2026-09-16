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
