# 02: Journey 创建与 LLM LearnUnit 生成/恢复

**What to build:** 学习者输入目标语言和学习目标后，系统为当前 Learning Journey 生成独立的教学 LearnUnit 集合并持久化，应用重启后仍能恢复同一套内容和进度入口。

**Blocked by:** 01: SAA 运行时对齐与 Agent/Graph 基础链路

**Status:** complete

- [x] 用户可以提交目标语言和学习目标并创建 Learning Journey。
- [x] 大模型按当前 Journey 生成 LearnUnit，非法或不完整的模型响应会被拒绝并给出可重试结果。
- [x] LearnUnit 内容保存到 SQLite，并与 Journey 建立独立关联。
- [x] 相同目标语言创建的不同 Journey 不共享 LearnUnit 或课程目录。
- [x] 应用重启后可恢复 Journey、LearnUnit 和当前学习入口。
- [x] 教学知识不写入 Agent Skill registry。

**Evidence:** Journey isolation、LLM curriculum validation、SQLite persistence and restore tests pass.
