# learn-agent-app

`learn-agent-app` v2 是一个 clean-slate 的 Agentic Programming Learning
Environment，目标技术栈为 Tauri 2、React/TypeScript、Monaco、Quarkus、
AgentScope Java、SQLite 和 GraalVM Native Image。

架构唯一来源是 [v2 Architecture Pack](docs/architecture-v2/README.md)。实施顺序见
[Roadmap](docs/architecture-v2/11-roadmap.md)，当前清场合同见
[Step 0](docs/architecture-v2/steps/00-architecture-contract.md)。

## 当前阶段：Step 5 — Practice Runtime

当前已具备独立 Learning Workspace 的文件读写与列表 API，以及通过
`ExecutionEnvironment` 执行 TypeScript `compile`、Vitest `run_tests` 和固定脚本
`run_program`。首次 compile/test 会在对应 Workspace 内使用固定的
`pnpm install --ignore-scripts` 初始化依赖。

Practice 已打通真实 HTTP + SQLite 闭环：编译/测试诊断可被 Tutor 通过安全的
Workspace tool loop 观察，验证成功后由应用层写入 PracticeEvidence 并推动 Learning
Domain。Practice verify 当前只支持 compile/tests；Lint 和 runtime 检查尚未形成固定
契约，要求这些检查的任务会被明确拒绝。

Practice 状态通过安全的 `VerifyResponse` 返回给 Practice UI，Tutor SSE 不包含
`practice.verified`。Sandbox、Permission、macOS arm64 和真实 OpenAI provider 尚未验收。

## 检查

```bash
pnpm check
```

当前 Windows 本地 `pnpm check` 已通过，包含前端 6 个单测和后端 67 个测试；这不代表
Sandbox/Permission、macOS arm64、真实 OpenAI provider 或完整 lint/runtime 能力已验收。
