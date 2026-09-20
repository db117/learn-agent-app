# Agent Runtime

## TutorAgent

`TutorAgent` 是唯一主 Agent，也是 Orchestrator。

典型输入 `TutorContext`：

```text
LearnerContext
JourneyContext
CurrentLearnUnit
PracticeContext
ProjectContext
DomainProgress
LongTermMemory
WorkspaceContext
AvailableSkills
AvailableTools
PermissionContext
```

## TutorAgent 能力

```text
Understand
Teach
Load Skill
Read Workspace
Call Tool
Observe Result
Give Hint
Create Plan
Request Permission
Delegate Subagent
Summarize
```

## TutorAgent 禁止能力

不能直接：

```text
mark unit completed
change mastery
skip current learning item
write database arbitrarily
write outside workspace
run arbitrary shell
```

## Runtime 分工

AgentScope 负责：Session、Workspace、Skill、Memory、Plan、Permission、MCP、Subagent、Compaction 等 Runtime 能力。

应用层负责：配置、Tool、业务上下文装配、事件投影、Domain 集成。

## Subagent

最终只有三个：

```text
ResearchAgent
DebugAgent
ReviewAgent
```

主要价值是专业化和上下文隔离，而不是为了“多 Agent”。
