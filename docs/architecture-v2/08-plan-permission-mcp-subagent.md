# Plan, Permission, MCP and Subagent

## Plan

简单问题无需 Plan；真实项目使用 Plan。

```text
Build TypeScript REST API
1. Initialize
2. Domain model
3. Persistence
4. Endpoints
5. Tests
6. Refactor
```

`ProjectMilestone` 是 Domain，`Agent Plan` 是 Runtime。

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
