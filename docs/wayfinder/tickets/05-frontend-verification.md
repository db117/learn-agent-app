# Frontend Flow 与 Native 验证

Status: complete with Native environment limitation  
Parent: [Phase 2 Learning Core map](../phase-2-learning-core-map.md)

## Question

如何把首屏改为 Welcome → Language → Profile → Diagnostic → Path/Dashboard，并以最小成本验证 JVM、TypeScript、Tauri、Native 和 SQLite 恢复？

## Implementation

React 已实现 Welcome/profile、Diagnostic、Result、Path/Lesson 和 Tutor SSE 页面；
`pnpm typecheck`、`pnpm lint`、`pnpm build`、`pnpm cargo:check`、`pnpm check` 和
`pnpm backend:test` 通过（17 个 JVM 测试）。`pnpm native:check` 已执行，但当前机器
只有 Eclipse Adoptium JDK 21，没有 GraalVM `native-image`，因此无法进入 Native 编译/启动阶段。
