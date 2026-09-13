package com.example.agent.api;

import java.util.List;

/**
 * 向 Tutor 会话发送消息的 HTTP 请求。
 *
 * @param content 用户消息正文
 * @param questionContext 页面当前题面；只用于本次调用，不写入用户消息记录
 */
public record SendMessageRequest(String content, QuestionContext questionContext) {

    public record QuestionContext(
            String phase,
            String prompt,
            List<Option> options,
            String starterCode,
            String answerDraft) {
    }

    public record Option(String id, String text) {
    }
}
