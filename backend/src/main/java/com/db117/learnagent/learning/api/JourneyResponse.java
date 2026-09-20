package com.db117.learnagent.learning.api;

import com.db117.learnagent.learning.application.JourneyApplicationService.LearningJourneySummary;
import com.db117.learnagent.learning.domain.Journey;

/**
 * Journey 的稳定 API 投影。
 *
 * @param id Journey 主键
 * @param goalDescription 用户确认的目标描述
 * @param status Journey 的 ACTIVE/ARCHIVED 状态
 * @param current 是否为当前用户选中的 Journey
 * @param learningJourneyId 已生成 LearningJourney 的主键；规划阶段为空
 * @param learningJourneyStatus LearningJourney 的 ACTIVE/COMPLETED 状态；尚未生成时为空
 * @param currentLearnUnitCode 当前 LearnUnit 编码；路径完成或尚未生成时为空
 */
public record JourneyResponse(
        long id,
        String goalDescription,
        String status,
        boolean current,
        Long learningJourneyId,
        String learningJourneyStatus,
        String currentLearnUnitCode) {
    public static JourneyResponse from(Journey journey) {
        return from(journey, null);
    }

    public static JourneyResponse from(
            Journey journey,
            LearningJourneySummary summary) {
        return new JourneyResponse(
                journey.id(),
                journey.goalDescription(),
                journey.status().name(),
                journey.current(),
                journey.learningJourneyId(),
                summary == null ? null : summary.status(),
                summary == null ? null : summary.currentLearnUnitCode());
    }
}
