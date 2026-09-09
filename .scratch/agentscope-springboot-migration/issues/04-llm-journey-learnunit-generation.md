# 04: Journey/LearnUnit 的 LLM 生成

**What to build:** 学习者提交目标语言、学习目标和背景后，系统通过 LLM 生成当前 Journey 的独立
LearnUnit、教学内容和适用题目，并以原子方式保存。

**Blocked by:** 01: Spring Boot WebFlux TutorAgent 流主链路；03: SQLite 新库、TutorSession 与 AgentState 恢复

**Status:** resolved

- [x] 用户可以提交目标语言、学习目标、主要编程语言、经验年限和背景描述。
- [x] LLM 为当前 Journey 生成独立的 LearnUnit 集合、教学内容和适用题目；不同 Journey 不共享生成出的课程目录。
- [x] LearnUnit 数量没有 4–8 的硬上限，内容较多时可以生成更多单元，但不能生成空内容或为了凑数重复内容。
- [x] 如果生成结果没有编码学习目标，则不创建 Coding Question；只有通过结构校验的编码内容才能进入 Assessment。
- [x] Java 校验生成结果的结构、必填字段和题型约束后，才保存 Journey、LearnUnit、Question 和相关路径数据。
- [x] LLM 不可用、超时、响应非法或校验失败时直接报错，不回退到静态课程、旧链路或 Mock 结果，也不留下部分数据。
- [x] React 能显示生成中的状态、成功结果和可恢复的错误信息；自动化测试覆盖成功、无编码题和失败边界。

## Answer

- 扩展 Journey curriculum 生成协议，支持独立 LearnUnit、教学内容和 Question；Question/ LearnUnit ID 按 Journey 隔离。
- 增加 Java 结构校验、无编码目标分支、生成失败零落库事务边界，并移除评估题目规划的旧题库 fallback；LearnUnit 与 Journey 的关系继续由现有 Learning Path 流程维护。
- React 展示生成中状态和可恢复错误；后端 44 项测试及完整项目检查通过。
