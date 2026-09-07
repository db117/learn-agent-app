package com.example.agent.learning.assessment;

import java.time.Instant;

/**
 * 一次评估尝试，允许同一 Assessment 下存在多次 Retry。
 *
 * @param id Attempt 主键
 * @param assessmentId 所属 Assessment
 * @param journeyId 所属 Journey，便于按 Journey 查询历史
 * @param skillCode 被评估技能；诊断 Attempt 可为空
 * @param attemptNumber 在同一 Assessment 下的递增序号
 * @param choiceScore 选择题百分比分数
 * @param codingScore Coding 百分比分数
 * @param totalScore 总分
 * @param passed 是否通过；进行中时为空
 * @param startedAt 开始时间
 * @param completedAt 提交时间；进行中时为空
 */
public record AssessmentAttempt(
        String id,
        String assessmentId,
        String journeyId,
        String skillCode,
        int attemptNumber,
        Integer choiceScore,
        Integer codingScore,
        Integer totalScore,
        Boolean passed,
        Instant startedAt,
        Instant completedAt) {
}
