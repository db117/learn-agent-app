# Product Vision

## 定位

`learn-agent-app` v2 是一个 **Agentic Programming Learning Environment**。

它不是单纯的聊天 Tutor、AI 课程生成器、IDE Clone 或 Agent Demo，而是把学习、真实编码和 Agent Runtime 结合起来。

## 两种学习模式

### Learn

解决“我不知道”。

```text
Explain → Example → Practice
```

### Practice

解决“我知道，但是不会写”。

```text
PracticeTask → Learning Workspace → User Code → Submit
→ Compile/Test → PracticeEvidence → Tutor Diagnose → Hint/Retry
→ Tutor judges readiness → Learner confirms → Learning Domain advances
```


## 唯一主 Agent

系统只有一个面向用户的主 Agent：`TutorAgent`。

TutorAgent 负责：理解上下文、教学、加载 Skill、使用 Tool、读取 Learning Workspace、评估学习情况、创建必要的多步 Plan、请求
Permission、委派 Subagent、汇总结果。

TutorAgent 不负责直接修改 mastery、completion，也不能绕过 PracticeEvidence 验证。

## 核心成功链路

```text
学习 TypeScript union type
→ Tutor 解释
→ 生成 PracticeTask
→ 用户在 Monaco 写代码
→ 用户提交练习
→ 应用自动编译并运行测试，获得真实 tsc diagnostic
→ 加载 diagnose-error Skill
→ 结合长期误区给 Hint
→ 用户修改
→ 再次提交并检查
→ 编译和测试结果作为 PracticeEvidence 写入 Domain
→ Tutor 判断是否已准备好继续
→ 学习者确认后由 Learning Domain 推进下一项
```
