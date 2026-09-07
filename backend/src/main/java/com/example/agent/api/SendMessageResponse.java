package com.example.agent.api;

/**
 * 启动一次 Tutor 运行后返回的异步追踪标识。
 *
 * @param runId Agent 运行标识
 * @param messageId 已保存的用户消息标识
 */
public record SendMessageResponse(String runId, String messageId) {
}
