package com.db117.learnagent.agent.api;

import java.util.List;

/** 创建或恢复 Session 的公开结果。 */
public record TutorSessionResponse(
        /** 稳定且不透明的 Session ID。 */
        String sessionId,
        /** 是否从 AgentScope 状态文件恢复了已有状态。 */
        boolean restored,
        /** 当前 LearningPathItem 对应的 LearnUnit 编码。 */
        String currentLearnUnitCode,
        /** 已完成的公开对话消息。 */
        List<TutorMessage> messages) {

    public TutorSessionResponse {
        messages = List.copyOf(messages == null ? List.of() : messages);
    }
}
