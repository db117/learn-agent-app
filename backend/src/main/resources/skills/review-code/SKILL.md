---
name: review-code
description: 对当前 Learning Workspace 的代码做面向正确性、可维护性和安全边界的审查，并按优先级报告发现。
---

# Review Code

先列出并读取与问题相关的文件，再围绕行为契约审查：正确性、边界条件、错误处理、测试覆盖、类型安全和
Workspace/ExecutionEnvironment 边界。只报告可由代码或真实工具结果支持的问题，按阻断级别从高到低排列，并给出文件和行号。

审查不等于修改。不要直接修改 Learning Domain 的掌握度、完成状态或 PracticeEvidence，也不要把工具参数、凭据或私有推理暴露给学习者。
