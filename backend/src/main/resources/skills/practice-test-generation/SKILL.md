---
name: practice-test-generation
description: 为当前 LearnUnit 生成一道基于现有内容的四选一练习题；当前 Step 7 只支持选择题。
---

# Practice Test Generation

由 TutorAgent 直接根据当前 LearnUnit 的标题、目标和内容生成题目，不调用生成类工具。

严格遵守以下流程：

1. 只根据当前 LearnUnit 生成一道单选题；不得引入内容之外的事实。
2. 题目必须恰好有四个选项，选项 ID 固定为 `a`、`b`、`c`、`d`，且只有一个选项正确。
3. 先在模型内部组织如下 JSON，再调用 `save_practice_test` 保存；不要把 `correctOptionId` 展示给学习者：

   ```json
   {"prompt":"题干","options":[{"id":"a","label":"..."},{"id":"b","label":"..."},{"id":"c","label":"..."},{"id":"d","label":"..."}],"correctOptionId":"a"}
   ```

4. 只向学习者展示保存工具返回的题干和四个选项，然后等待学习者回答。
5. 收到选项 ID 后调用 `verify_practice_test`；根据工具返回结果解释对错，不自行修改掌握度、完成状态或证据。

不要输出内部 JSON、正确答案、模型私有推理或数据库细节。

这项能力不替代固定的 code-task、Vitest 或 ExecutionEnvironment 流程。选择题工具负责把判题结果记录为
PracticeEvidence，并由 Learning Domain 按既有规则推进进度。
