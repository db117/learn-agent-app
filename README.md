# learn-agent-app

`learn-agent-app` v2 是一个 clean-slate 的 Agentic Programming Learning
Environment，目标技术栈为 Tauri 2、React/TypeScript、Monaco、Quarkus、
AgentScope Java、SQLite 和 GraalVM Native Image。

架构唯一来源是 [v2 Architecture Pack](docs/architecture-v2/README.md)。实施顺序见
[Roadmap](docs/architecture-v2/11-roadmap.md)，当前清场合同见
[Step 0](docs/architecture-v2/steps/00-architecture-contract.md)。

## Step 0

已移除旧学习流程、旧 REST API、旧 SQLite schema、旧 Agent runtime、旧 Tutor session、
旧前端业务代码和冲突的旧架构文档；保留桌面壳、前端构建链、Maven/Cargo 工具链、静态资源
以及 Git 历史。后续从 Step 1 建立新的 Quarkus 运行骨架。

## 检查

```bash
pnpm typecheck
pnpm lint
pnpm build
pnpm cargo:check
```
