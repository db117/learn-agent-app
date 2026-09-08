package com.example.agent.learning.workflow;

import java.time.Instant;

/** A durable, deterministic learning workflow state change. */
public record WorkflowTransition(
        String id,
        String journeyId,
        String fromState,
        String action,
        String toState,
        String payloadJson,
        Instant createdAt) {

    public WorkflowTransition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("transition id is required");
        if (journeyId == null || journeyId.isBlank()) throw new IllegalArgumentException("journey id is required");
        if (action == null || action.isBlank()) throw new IllegalArgumentException("transition action is required");
        if (toState == null || toState.isBlank()) throw new IllegalArgumentException("transition target is required");
        if (payloadJson == null || payloadJson.isBlank()) payloadJson = "{}";
        if (createdAt == null) createdAt = Instant.now();
    }
}
