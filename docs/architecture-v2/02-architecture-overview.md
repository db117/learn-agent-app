# Architecture Overview

## 总体架构

```text
┌──────────────────────────────────────────────────┐
│ Desktop: Tauri 2                                │
│ React + TypeScript + Monaco                     │
│ Learn / Practice / Project / Agent Activity     │
└─────────────────────┬────────────────────────────┘
                      │ HTTP + SSE
                      ▼
┌──────────────────────────────────────────────────┐
│ Quarkus Application                             │
│                                                  │
│ Learning Domain                                 │
│ Practice Domain                                 │
│ Project Domain                                  │
│                                                  │
│ Agent Application Layer                         │
│   TutorAgent / TutorContext / Tools / Events    │
│                                                  │
│ AgentScope Harness Runtime                      │
│   Workspace / Session / Skill / Memory          │
│   Plan / Permission / Sandbox / MCP             │
│   Subagents / Compaction                        │
│                                                  │
│ SQLite + Filesystem                             │
└──────────────────────────────────────────────────┘
```

## 后端组织

采用 feature-first + hexagonal boundary：

```text
com.db117.learnagent

bootstrap/
shared/

learning/
  domain/
  application/
  infrastructure/
  api/

practice/
  domain/
  application/
  infrastructure/
  api/

project/
  domain/
  application/
  infrastructure/
  api/

language/
workspace/
agent/
execution/
persistence/sqlite/
config/
```

禁止重新回到全局 `controller/service/repository/entity/dto` 结构。

## 架构不变量

- Domain 不依赖 AgentScope。
- AgentScope Runtime 不保存权威学习事实。
- UI 不直接消费 AgentScope Raw Event。
- Tool 是 Agent 与应用交互的正式边界。
- 任意 shell 不是默认能力。
- Subagent 不直接面向用户。
- 初期保持单 Maven module，用 package boundary 控制职责。
