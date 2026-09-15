package com.db117.learnagent.agent.api;

/** Session 恢复时返回给 UI 的公开对话消息；只包含 USER/ASSISTANT 文本。 */
public record TutorMessage(
        /** 消息角色，当前只返回 {@code user} 或 {@code assistant}。 */
        String role,
        /** 已完成的公开文本。 */
        String text,
        /** AgentScope 生成的公开时间戳。 */
        String timestamp) {
}
