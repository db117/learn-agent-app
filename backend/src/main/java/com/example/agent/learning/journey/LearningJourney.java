package com.example.agent.learning.journey;

import java.time.Instant;

/**
 * 学习者针对一种语言创建的学习旅程。
 *
 * @param id Journey 主键
 * @param userId 本地用户标识；MVP 中由应用配置提供
 * @param languageCode 目标学习语言
 * @param goal Journey 名称或学习目标摘要
 * @param status Journey 当前生命周期状态
 * @param createdAt 创建时间
 * @param updatedAt 最近一次状态或路径变化时间
 */
public record LearningJourney(
        String id,
        String userId,
        String languageCode,
        String goal,
        JourneyStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
