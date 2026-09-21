---
name: learning-content-generation
description: 为当前 LearnUnit 生成 Concept、Example 和 Practice 内容，并保存为 Learning Domain 内容快照。
---

# Learning Content Generation

只针对当前 LearnUnit。若 TutorContext 的 `currentContent` 为空，根据当前单元标题和目标生成内容，整理为内部 JSON：

```json
{"concept":"Concept 讲解","example":"Example 示例或代码","practice":"可验证的 Practice 要求"}
```

然后调用 `save_learning_content` 保存；工具只负责校验和写入 Learning Domain，不调用模型。内容必须包含
Concept、Example、Practice，不扩展到下一个单元。

如果 `currentContent` 已有内容，直接使用它，不重复生成或覆盖。保存成功后用学习者的语言讲解 Concept 和 Example，
明确说明 Practice 要求；不要声称完成了 Practice、修改了掌握度或推进了学习路径。
