# Codex Execution Rules

## 总原则

Codex 以 Architecture Contract 为最高约束。

禁止为了完成局部任务：修改核心边界、引入兼容层、提前实现未来阶段、扩大任务范围、自行改变技术栈。

## 一次只做一个小任务

不要直接执行“实现 Step 5”，而应拆成：

```text
05.1 Practice domain
05.2 ExecutionEnvironment
05.3 TypeScript compiler
05.4 Vitest runner
05.5 Agent tools
05.6 Tutor integration
05.7 SSE events
05.8 Monaco integration
05.9 E2E
```

## 修改前

必须先读：`AGENTS.md`、`CONTEXT.md`、相关 architecture docs、当前 Step 文档。

## 修改中

- 不删除无关用户改动。
- 不引入无关依赖。
- 不顺手重构无关代码。
- 不提交或 push。
- 不创建兼容 API。
- 不用 TODO 代替关键实现。

## 修改后

必须执行 format、unit/integration tests、frontend/backend checks，并输出：Changed / Why / Tests / Remaining / Architecture
impact。

正常情况下 `Architecture impact` 必须是 `None`。
