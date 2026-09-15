package com.db117.learnagent.agent.api;

/** 创建或恢复 Tutor Session 的请求。 */
public record CreateTutorSessionRequest(
        /** Learning Domain 中的 Learner ID。 */
        Long learnerId,
        /** Learning Domain 中的 LearningJourney ID。 */
        Long journeyId) {
}
