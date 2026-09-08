package com.example.agent.learning.assessment;

import java.time.Instant;

/**
 * 一份固定题集的评估定义。
 *
 * <p>题目通过 {@code assessment_question} 关联表在创建时固定；Retry
 * 只会新增 {@link AssessmentAttempt}，不会改变这份 Assessment 的题集。</p>
 *
 * @param id Assessment 主键
 * @param journeyId 所属 Journey
     * @param learnUnitCode 评估对应的 LearnUnit；诊断评估时为空
     * @param type 诊断评估或 LearnUnit 评估
 * @param status 当前评估状态
 * @param createdAt 创建时间
 * @param completedAt 完成时间；未完成时为空
 */
public record Assessment(
        String id,
        String journeyId,
        String learnUnitCode,
        AssessmentType type,
        AssessmentStatus status,
        Instant createdAt,
        Instant completedAt) {
}
