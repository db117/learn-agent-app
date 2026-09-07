# ADK 集成

`AgentConfiguration` 创建一个名为 `tutor_agent` 的 `LlmAgent`、一个
`InMemorySessionService` 和一个 ADK `Runner`。`TutorAgentService` 为每个已持久化
会话创建 ADK 会话，并通过 `Runner.runAsync` 顺序执行工具。

第一阶段唯一的工具是 `EchoTool`。它通过 ADK 的 `FunctionTool.create` 注册；ADK
接收提供商的函数声明、执行工具、发出工具调用和工具结果事件，然后再次请求模型。

`SpringAiLlm` 是唯一的适配器。它将 ADK `LlmRequest` 内容和函数声明转换为
Spring AI `Prompt`，调用 Spring AI `ChatModel`，再将响应转换回 ADK
`LlmResponse`。适配器会向提供商暴露工具定义，但其回调会有意抛出异常：工具由
ADK 负责执行，而不是由 Spring AI 负责。

测试 `TutorAgentToolLoopTest` 在这个公开边界使用脚本化 `ChatModel`。它验证完整
路径：提供商工具调用 → ADK `EchoTool` → ADK 工具结果事件 → 包含
`ToolResponseMessage` 的第二次提供商请求 → 最终 Assistant 事件。

提供商密钥从 `OPENAI_API_KEY` 读取，仓库中不保存任何凭据。`OPENAI_MODEL` 默认值
为 `gpt-5-mini`，无需修改 Agent 代码即可调整。
