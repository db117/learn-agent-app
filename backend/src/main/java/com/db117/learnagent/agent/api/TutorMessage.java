package com.db117.learnagent.agent.api;

/**
 * Session 恢复时返回给 UI 的公开对话消息；只包含 USER/ASSISTANT 文本。
 *
 * @param role 消息角色，当前只返回 {@code user} 或 {@code assistant}
 * @param text 已完成的公开文本
 * @param timestamp AgentScope 生成的公开时间戳
 */
public record TutorMessage(
        String role,
        String text,
        String timestamp) {
}
