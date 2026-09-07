package com.example.agent.persistence;

import java.time.Instant;

/**
 * 会话中可展示的用户或助手消息。
 *
 * @param id 消息唯一标识
 * @param sessionId 所属会话标识
 * @param role 消息角色，当前使用 user 或 assistant
 * @param content 消息正文
 * @param createdAt 创建时间
 */
public record MessageRecord(
        String id, String sessionId, String role, String content, Instant createdAt) {
}
