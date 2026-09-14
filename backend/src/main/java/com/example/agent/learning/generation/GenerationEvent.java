package com.example.agent.learning.generation;

import java.time.Instant;

/** 单次生成运行事件的公开安全投影。 */
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
