package com.example.agent.learning.journey;

import java.time.Instant;

/**
 * 某个 Journey 对单个技能的学习状态和历史最佳成绩。
 *
 * @param journeyId 所属 Journey
 * @param skillCode 技能编码
 * @param status 当前技能状态
 * @param masteryScore 掌握度；按历史成绩最大值维护
 * @param bestAssessmentScore 历史评估最高总分
 * @param attemptCount 已完成的评估次数
 * @param passReason 通过来源，诊断通过或学习后通过
 * @param startedAt 首次进入学习的时间
 * @param passedAt 最近一次通过时间
 * @param skippedAt 跳过时间；跳过不代表通过
 */
public record LearnerSkill(
        String journeyId,
        String skillCode,
        LearnerSkillStatus status,
        int masteryScore,
        int bestAssessmentScore,
        int attemptCount,
        PassReason passReason,
        Instant startedAt,
        Instant passedAt,
        Instant skippedAt) {
}
