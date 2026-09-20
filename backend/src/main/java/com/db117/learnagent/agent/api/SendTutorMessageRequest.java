package com.db117.learnagent.agent.api;

/**
 * 发送一个 Tutor Turn 的请求。
 *
 * @param turnId 客户端生成的幂等 Turn ID
 * @param text 学习者输入的文本
 */
public record SendTutorMessageRequest(
        String turnId,
        String text) {
}
