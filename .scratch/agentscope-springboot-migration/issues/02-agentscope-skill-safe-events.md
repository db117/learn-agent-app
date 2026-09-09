# 02: AgentScope Skill 与安全事件展示

**What to build:** TutorAgent 需要工程能力时，通过 AgentScope Skill registry 发现并加载一个 classpath Skill，用户可以在
React 中看到安全的 Skill 加载和 reasoning 阶段摘要。

**Blocked by:** 01: Spring Boot WebFlux TutorAgent 流主链路

**Status:** ready-for-agent

- [ ] AgentScope 能发现 Skill metadata，并按 AgentScope 机制按需加载 Skill；不把 Skill 当作 LearnUnit 或学习进度对象。
- [ ] WebFlux SSE 转换出 Skill 加载开始、完成和安全 reasoning/thinking 摘要事件，React 能以可折叠进度形式展示。
- [ ] 展示内容不包含原始思维链、完整系统提示词、完整 SKILL.md、references、敏感参数、本地完整路径、凭据或原始堆栈。
- [ ] Skill 加载失败会产生明确的项目错误和终止事件，不伪装成普通文本回答。
- [ ] 自动化测试能证明 Skill discovery/load 和事件投影真实发生，而不是只根据最终回答推断。
