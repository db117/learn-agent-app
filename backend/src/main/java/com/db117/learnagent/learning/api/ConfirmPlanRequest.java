package com.db117.learnagent.learning.api;

/** 确认规划草稿并生成 LearningJourney 的请求。 */
public record ConfirmPlanRequest(
        /** TutorAgent 最近生成、且由用户确认的规划草稿文本。 */
        String plan) {
}
