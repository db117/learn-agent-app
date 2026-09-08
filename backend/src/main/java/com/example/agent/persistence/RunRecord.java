package com.example.agent.persistence;

import java.time.Instant;

/**
 * 一次 TutorAgent 执行的持久化摘要。
 *
 * @param id 运行唯一标识
 * @param sessionId 所属会话标识
 * @param status 运行状态，例如 RUNNING、COMPLETED 或 FAILED
 * @param errorMessage 失败原因；成功时为空
 * @param startedAt 开始时间
 * @param completedAt 完成时间；运行中时为空
 */
public record RunRecord(
        String id,
        String sessionId,
        String status,
        String errorMessage,
        Instant startedAt,
        Instant completedAt) {
}
