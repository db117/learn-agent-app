package com.example.agent.api;

import com.example.agent.persistence.MessageRecord;

import java.time.Instant;
import java.util.List;

/**
 * 包含历史消息的 Tutor 会话详情。
 *
 * @param id 会话标识
 * @param title 会话标题
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param messages 按创建顺序排列的历史消息
 */
public record SessionDetailResponse(
        String id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        List<MessageRecord> messages) {
}
