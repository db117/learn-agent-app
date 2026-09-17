package com.db117.learnagent.learning.api;

import com.db117.learnagent.learning.application.JourneyApplicationService.LearningJourneySummary;
import com.db117.learnagent.learning.domain.Journey;

public record JourneyResponse(
        /** Journey 主键。 */
        long id,
        /** 用户确认的目标描述。 */
        String goalDescription,
        /** Journey 的 ACTIVE/ARCHIVED 状态。 */
        String status,
        /** 是否为当前用户选中的 Journey。 */
        boolean current,
        /** 已生成 LearningJourney 的主键；规划阶段为空。 */
        Long learningJourneyId,
        /** LearningJourney 的 ACTIVE/COMPLETED 状态；尚未生成时为空。 */
        String learningJourneyStatus,
        /** 当前 LearnUnit 编码；路径完成或尚未生成时为空。 */
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
