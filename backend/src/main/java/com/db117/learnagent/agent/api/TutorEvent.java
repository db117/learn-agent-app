package com.db117.learnagent.agent.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 面向 UI 的安全 Tutor 事件；不承载原始 Event、完整回答或私有推理。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TutorEvent(
        /** 当前 Turn 内单调递增的事件 ID。 */
        long id,
        /** 稳定的 UI 事件类型。 */
        TutorEventType type,
        /** 不透明的 Tutor Session ID。 */
        String sessionId,
        /** 客户端生成并用于幂等的 Turn ID。 */
        String turnId,
        /** 当前 Turn 内单调递增的序号。 */
        long sequence,
        /** 安全的活动文本或回答增量；非文本事件为空。 */
        String text,
        /** 稳定的公开错误码；成功事件为空。 */
        String errorCode) {
}
