package com.example.agent.api;

/**
 * 创建 Tutor 会话的 HTTP 请求。
 *
 * @param title 会话标题；为空时由服务端使用默认标题
 */
public record CreateSessionRequest(String title) {
}
