package com.example.agent.api;

import com.example.agent.persistence.SessionRecord;

import java.time.Instant;

/**
 * Tutor 会话列表中的摘要响应。
 *
 * @param id 会话标识
 * @param title 会话标题
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
public record SessionResponse(String id, String title, Instant createdAt, Instant updatedAt) {

    public static SessionResponse from(SessionRecord session) {
        return new SessionResponse(session.id(), session.title(), session.createdAt(), session.updatedAt());
    }
}
