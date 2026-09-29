# Plan, Permission, MCP and Subagent

## Plan

简单交互无需 Plan；需要拆解的多步学习或编码任务可以使用 Plan。

```text
Implement a TypeScript practice task
1. Initialize
2. Domain model
3. Persistence
4. Endpoints
5. Tests
6. Refactor
```

Plan 是 Agent Runtime 中的任务组织状态，不是 Learning Domain 的完成事实。

## Permission

三档：`ALLOW / ASK / DENY`。

| 操作                     | 默认策略 |
|--------------------------|----------|
| read workspace           | ALLOW    |
| compile/test             | ALLOW    |
| write workspace          | ALLOW    |
| install dependency       | ASK      |
| network access           | ASK      |
| delete file              | ASK      |
| outside workspace        | DENY     |
| arbitrary system command | DENY     |

## MCP

MCP 只服务外部能力，例如 GitHub、Browser、语言官方文档、npm/Maven/crates.io。

LearningEngine、SQLite、Workspace、compile/test 不做 MCP 化，直接 Java Tool。

## Subagent

```text
TutorAgent
├── ResearchAgent
├── DebugAgent
└── ReviewAgent
```

Subagent 不允许直接修改 Domain、扩大权限、创建下级 Subagent 或直接对用户给最终答复。
