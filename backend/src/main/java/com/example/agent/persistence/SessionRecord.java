package com.example.agent.persistence;

import java.time.Instant;

/**
 * SQLite 中的 Tutor 会话摘要。
 *
 * @param id 会话唯一标识，同时也是 TutorAgent thread 的标识
 * @param userId 会话所属用户标识
 * @param title 会话标题
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
public record SessionRecord(
        String id, String userId, String title, Instant createdAt, Instant updatedAt) {
}
