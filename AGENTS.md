# AGENTS.md

## 架构合同

本仓库是 clean-slate v2 实现。

1. 不为 v1 添加向后兼容。
2. Domain State 与 Agent State 必须保持分离。
3. Learning Domain 是 score、mastery、completion 和 assessment 的唯一权威来源。
4. AgentScope 负责 session、memory、skill、plan、MCP、permission 和 subagents 等 Runtime 能力。
5. Language Pack 是产品插件；Skill 是 Agent capability。
6. UI 不得直接消费 AgentScope raw event。
7. 任意 shell 执行默认禁止。
8. 代码执行必须通过 ExecutionEnvironment。
9. TutorAgent 是唯一面向用户的主 Agent。
10. Subagent 只用于专业化和上下文隔离。
11. 除非明确要求，不要实现路线图中的后续阶段。
12. 每个任务必须有明确、狭窄的修改范围和验证方式。

## 唯一依据

修改代码前读取 `docs/architecture-v2/` 中与任务相关的文档；执行规则见
`docs/architecture-v2/codex/execution-rules.md`。

## 注释规则

- record 的注释要细到字段级别
- 代码注释使用中文；代码标识符、库名和协议名保留原文。
- 注释解释业务约束、设计原因、公共 API/字段契约和非显然逻辑，并保持靠近被解释的代码；行为变化时同步更新注释。
- 直观代码保持简洁，注释提供代码本身读不出的信息。
- 很长的链式调用分行书写，并在链前说明整体意图；中间步骤语义不明显时解释关键转换；出现复杂分支、副作用或错误处理时拆成有意义的局部变量或方法。

```java
// 生成已完成 LearnUnit 的唯一名称列表；整条链只读，不修改学习状态。
var masteredNames = path.stream()
                .filter(item -> item.status() == LearningPathItemStatus.COMPLETED)
                .map(item -> unitsByCode.get(item.learnUnitCode()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(LearnUnit::sequence))
                .map(LearnUnit::name)
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
```

解释：这条链依次筛选已完成项目、查找对应
LearnUnit、过滤缺失值、按课程顺序排序、提取名称、过滤空名称、去除首尾空白、去重并收集结果。每一步都保持单一且无副作用；某一步需要复杂条件或产生副作用时，应拆开表达。
