package com.db117.learnagent.agent.api;

/** 取消请求的公开结果。 */
public record TutorCancelResponse(
        /** 是否找到并请求取消了活动 Turn。 */
        boolean cancelled) {
}
