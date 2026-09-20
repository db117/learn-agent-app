package com.db117.learnagent.agent.api;

/**
 * 创建或恢复 Tutor Session 的请求。
 *
 * @param learnerId Learning Domain 中的 Learner ID
 * @param journeyId Learning Domain 中的 Journey ID，而不是 Agent Session ID
 * @param mode 规划或学习模式；未提供时按普通学习处理
 */
public record CreateTutorSessionRequest(
        Long learnerId,
        Long journeyId,
        TutorSessionMode mode) {

    public CreateTutorSessionRequest(Long learnerId, Long journeyId) {
        this(learnerId, journeyId, TutorSessionMode.LEARNING);
    }
}
