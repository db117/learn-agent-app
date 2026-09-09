package com.example.agent.persistence;

/** Stable application error for an unreadable or unavailable persisted AgentState. */
public class AgentStatePersistenceException extends IllegalStateException {

    public AgentStatePersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public AgentStatePersistenceException(String message) {
        super(message);
    }
}
