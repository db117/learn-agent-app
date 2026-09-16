package com.db117.learnagent.agent.api;

/** 创建或恢复 Tutor Session 的请求。 */
public record CreateTutorSessionRequest(
        /** Learning Domain 中的 Learner ID。 */
        Long learnerId,
        /** Learning Domain 中的 Journey ID，而不是 Agent Session ID。 */
        Long journeyId,
        /** 规划或学习模式；未提供时按普通学习处理。 */
        TutorSessionMode mode) {

    public CreateTutorSessionRequest(Long learnerId, Long journeyId) {
        this(learnerId, journeyId, TutorSessionMode.LEARNING);
    }
}
