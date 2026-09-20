package com.db117.learnagent.agent.api;

import java.util.List;

/**
 * 创建或恢复 Session 的公开结果。
 *
 * @param sessionId 稳定且不透明的 Session ID
 * @param restored 是否从 AgentScope 状态文件恢复了已有状态
 * @param currentLearnUnitCode 规划模式尚无当前学习项时为空
 * @param mode 当前 Session 是路径规划还是普通学习
 * @param messages 已完成的公开对话消息
 */
public record TutorSessionResponse(
        String sessionId,
        boolean restored,
        String currentLearnUnitCode,
        TutorSessionMode mode,
        List<TutorMessage> messages) {

    public TutorSessionResponse {
        messages = List.copyOf(messages == null ? List.of() : messages);
    }
}
