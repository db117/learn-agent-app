# learn-agent-app

`learn-agent-app` v2 是一个 clean-slate 的 Agentic Programming Learning
Environment，目标技术栈为 Tauri 2、React/TypeScript、Monaco、Quarkus、
AgentScope Java、SQLite 和 GraalVM Native Image。

架构唯一来源是 [v2 Architecture Pack](docs/architecture-v2/README.md)。实施顺序见
[Roadmap](docs/architecture-v2/11-roadmap.md)，当前清场合同见
[Step 0](docs/architecture-v2/steps/00-architecture-contract.md)。

## 当前阶段：Step 6 — Learn Mode

当前已具备确认 LearningJourney、读取当前 LearnUnit、恢复 LEARNING Tutor Session，以及
通过独立 Learning Workspace 完成 TypeScript Practice 的能力。首次 compile/test 会在对应
Workspace 内使用固定的 `pnpm install --ignore-scripts` 初始化依赖。

Learn Mode 已打通真实 HTTP + SQLite 闭环：Explain、Example、Practice 共享当前
LearnUnit 上下文；compile/tests 通过后由应用层写入 PracticeEvidence，并由 Learning
Domain 推进下一个 LearnUnit 或进入 `COMPLETED`。Practice verify 当前只支持 compile/tests；
Lint 和 runtime 检查尚未形成固定契约，要求这些检查的任务会被明确拒绝。

Practice 状态通过安全的 `VerifyResponse` 返回给 Practice UI，Tutor SSE 不包含
`practice.verified`。Sandbox、Permission、macOS arm64 和真实 OpenAI provider 尚未验收。

## 检查

```bash
pnpm check
```

当前本地 `pnpm check` 已通过，包含前端 8 个单测和后端 70 个测试；这不代表
Sandbox/Permission、macOS arm64、真实 OpenAI provider 或完整 lint/runtime 能力已验收。

浏览器 E2E：首次运行先安装 Playwright Chromium，然后执行 `pnpm e2e:browser`。测试会启动
Vite、Quarkus、隔离临时 SQLite 和本地确定性 Tutor Model；浏览器通过真实页面完成规划、Tutor
讲解、Practice 失败与修复、Session rollover 以及路径完成，不依赖真实 OpenAI 凭据。
