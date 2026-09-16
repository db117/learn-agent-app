# learn-agent-app

`learn-agent-app` v2 是一个 clean-slate 的 Agentic Programming Learning
Environment，目标技术栈为 Tauri 2、React/TypeScript、Monaco、Quarkus、
AgentScope Java、SQLite 和 GraalVM Native Image。

架构唯一来源是 [v2 Architecture Pack](docs/architecture-v2/README.md)。实施顺序见
[Roadmap](docs/architecture-v2/11-roadmap.md)，当前清场合同见
[Step 0](docs/architecture-v2/steps/00-architecture-contract.md)。

## 当前阶段：Step 3.5

本阶段为本地单用户设置 Learner 背景能力，创建和选择 Journey，并在 Tutor 规划会话中准备 LearningJourney。
Language Pack、Workspace、Practice 和 Learn 继续按路线图的后续阶段实现。

## 检查

```bash
pnpm typecheck
pnpm lint
pnpm build
pnpm cargo:check
```
