package com.db117.learnagent.agent.api;

import java.util.List;

/** 创建或恢复 Session 的公开结果。 */
public record TutorSessionResponse(
        /** 稳定且不透明的 Session ID。 */
        String sessionId,
        /** 是否从 AgentScope 状态文件恢复了已有状态。 */
        boolean restored,
        /** 规划模式尚无当前学习项时为空。 */
        String currentLearnUnitCode,
        /** 当前 Session 是路径规划还是普通学习。 */
        TutorSessionMode mode,
        /** 已完成的公开对话消息。 */
        List<TutorMessage> messages) {

    public TutorSessionResponse {
        messages = List.copyOf(messages == null ? List.of() : messages);
    }
}
