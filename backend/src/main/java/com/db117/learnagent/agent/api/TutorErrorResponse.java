package com.db117.learnagent.agent.api;

/** Tutor API 的稳定错误响应；不透传上游或内部异常文本。 */
public record TutorErrorResponse(
        /** 稳定的公开错误码。 */
        String code,
        /** 面向用户的短消息。 */
        String message) {
}
