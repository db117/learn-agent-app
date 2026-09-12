package com.example.agent.learning.generation;

import java.time.Instant;

/** Public, safe projection of one generation run event. */
public record GenerationEvent(
        long sequence,
        String runId,
        String operation,
        String eventType,
        String stage,
        String author,
        String content,
        String status,
        long elapsedMs,
        Instant timestamp,
        Object preview,
        String resourceType,
        String resourceId) {
}
