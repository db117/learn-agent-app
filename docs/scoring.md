# Scoring

每道题先按自己的 `maxScore` 计算类型百分比：

```text
choiceScore = choiceEarned / choiceMax × 100
codingScore = codingEarned / codingMax × 100
```

同时存在两种题型时：

```text
totalScore = round(choiceScore × 0.4 + codingScore × 0.6)
```

只有一种题型时，该类型归一化为总分 100%；没有题时总分为 0。Java 会拒绝负分、超过
题目最大分的评分和未知题型。

LearnUnit 默认要求 `totalScore >= 80`，且只要存在 Coding 题就要求 `codingScore >= 70`。
诊断使用 `totalScore >= 85`，并要求至少两道有效证据。79 失败、80 在无 Coding 限制时
通过；Coding 69 失败，70 才能满足 Coding 下限。
