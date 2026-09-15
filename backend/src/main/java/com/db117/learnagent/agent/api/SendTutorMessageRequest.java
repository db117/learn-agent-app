package com.db117.learnagent.agent.api;

/** 发送一个 Tutor Turn 的请求。 */
public record SendTutorMessageRequest(
        /** 客户端生成的幂等 Turn ID。 */
        String turnId,
        /** 学习者输入的文本。 */
        String text) {
}
