package com.example.agent.persistence;

/** 持久化 AgentState 不可读取或不可用时使用的稳定应用错误。 */
public class AgentStatePersistenceException extends IllegalStateException {

    public AgentStatePersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public AgentStatePersistenceException(String message) {
        super(message);
    }
}
