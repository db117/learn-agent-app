# 08: Native 最终验收与 ADK 删除

**What to build:** 新的 Spring AI Alibaba 学习流程在正式 Native backend 中完成构建、启动和健康验收后，移除旧 ADK runtime 及其适配代码和文档，最终系统只保留一个 TutorAgent。

**Blocked by:** 07: React/Tauri 全链路切换与协议回归

**Status:** blocked

- [x] JVM、前端、Cargo、Agent、Graph、SQLite、SSE 和完整学习流程测试通过。
- [ ] Native backend 可以编译、启动并通过 health、TutorAgent、Graph 和 SQLite 验收。
- [ ] Native 反射、资源、序列化和代理配置满足实际运行路径。
- [x] 确认应用运行不再依赖 ADK runtime、ADK Agent、adapter 或 event bridge。
- [x] 删除 ADK dependencies、旧实现和过时架构文档，更新项目说明。
- [x] Native 作为正式迁移后的最终验收，不恢复或新增 Phase 0。

**Blocker:** Spring AOT completed, but `pnpm native:check` is blocked at Native Image compilation because the current Eclipse Adoptium JDK does not contain `native-image`; install a GraalVM JDK and rerun the Native acceptance.
