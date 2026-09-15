# Global Definition of Done

任何阶段或任务必须同时满足：

## Architecture

- 未违反 Architecture Contract。
- 未引入 Legacy/V1 compatibility。
- 未重复实现 Agent Runtime。
- 未破坏 Domain / Agent State 分离。
- 未未经允许扩展权限。

## Code

- package boundary 符合 feature-first。
- 无无意义 TODO、dead code、复制粘贴式兼容逻辑。
- 新 API 有明确用途。

## Tests

根据任务执行：Unit、Integration、E2E、Frontend type check、Backend test、必要时 Native compatibility。

## Agent

若涉及 Agent：Tool 权限最小化；不暴露 chain-of-thought；不直接修改权威学习状态；Raw Event 必须经过 TutorEvent
Projection；Subagent 只返回结果给 Tutor。

## Final Report

```text
Changed:
- ...

Validation:
- ...

Architecture impact:
- None

Known limitations:
- ...
```
