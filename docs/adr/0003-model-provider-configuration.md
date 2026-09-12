# ADR 0003: 在 App 中保存并配置模型提供商

Status: accepted

Date: 2026-09-12

## Context

模型配置原先只能通过 `OPENAI_BASE_URL`、`OPENAI_API_KEY` 和 `OPENAI_MODEL` 提供，桌面 App 没有配置入口。
AgentScope 的 `Model`、`HarnessAgent` 以及学习侧的模型组件都在 JVM 启动时创建，因此修改配置不能自动改变当前进程中的模型实例。

## Decision

模型提供商配置采用一个 App-wide 的当前配置，协议使用 OpenAI-compatible 接口，以支持 OpenAI 及兼容该协议的服务。
配置由 App 通过现有 SQLite `setting` 数据保存；显式环境变量优先于 App 保存的值，保存后重启 JVM 后端使配置生效。

## Consequences

- 不新增配置数据库或第二套模型运行时；同一配置继续供 TutorAgent、课程生成、诊断选题和 Coding 评分使用。
- 原生 Anthropic、Google 等非 OpenAI-compatible 协议不在本次范围内。
- API Key 会与 SQLite 数据库一起保存，并可能包含在数据库导出文件中；后续若需要更强隔离，再迁移到操作系统凭据存储。
- 运行中的 Agent 不会被热切换；桌面端保存后重启受管 JVM，开发模式需要手动重启后端。
