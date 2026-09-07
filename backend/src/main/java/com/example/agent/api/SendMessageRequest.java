package com.example.agent.api;

/**
 * 向 Tutor 会话发送消息的 HTTP 请求。
 *
 * @param content 用户消息正文
 */
public record SendMessageRequest(String content) {
}
