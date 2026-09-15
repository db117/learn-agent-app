package com.db117.learnagent.agent.api;

import com.fasterxml.jackson.annotation.JsonValue;

/** Tutor SSE 的稳定事件类型；UI 不直接读取 AgentScope Event。 */
public enum TutorEventType {
    TURN_STARTED("turn.started"),
    ACTIVITY("activity"),
    MESSAGE_DELTA("message.delta"),
    TURN_COMPLETED("turn.completed"),
    TURN_FAILED("turn.failed"),
    TURN_CANCELLED("turn.cancelled");

    private final String value;

    TutorEventType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
