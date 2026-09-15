# Product Vision

## 定位

`learn-agent-app` v2 是一个 **Agentic Programming Learning Environment**。

它不是单纯的聊天 Tutor、AI 课程生成器、IDE Clone 或 Agent Demo，而是把学习、真实编码和 Agent Runtime 结合起来。

## 三种学习模式

### Learn

解决“我不知道”。

```text
Explain → Example → Check Understanding → Prepare Practice
```

### Practice

解决“我知道，但是不会写”。

```text
PracticeTask → Workspace → User Code → Compile/Test → Diagnose → Hint → Retry → Verify
```

### Project

解决“我会做小题，但是不会做真实项目”。

```text
Goal → Plan → Milestones → Build → Research → Debug → Review → Evaluate
```

## 唯一主 Agent

系统只有一个面向用户的主 Agent：`TutorAgent`。

TutorAgent 负责：理解上下文、教学、加载 Skill、使用 Tool、读取 Workspace、创建 Plan、请求 Permission、委派 Subagent、汇总结果。

TutorAgent 不负责直接修改 score、mastery、completion，也不能绕过 Assessment。

## 核心成功链路

```text
学习 TypeScript union type
→ Tutor 解释
→ 生成 PracticeTask
→ 用户在 Monaco 写代码
→ Tutor 调 compile
→ 获得真实 tsc diagnostic
→ 加载 diagnose-error Skill
→ 结合长期误区给 Hint
→ 用户修改
→ run_tests
→ PracticeEvidence 写入 Domain
→ 独立 Assessment
```
