package com.example.agent.learning.journey;

import com.example.agent.learning.catalog.CurriculumGenerator;

import java.time.Instant;

/** Journey 首次大纲生成的框架无关实时事件；事件只在本地运行时和 JSONL 日志中存在。 */
public record JourneyDraftEvent(
        long sequence,
        String runId,
        String author,
        String eventType,
        String content,
        CurriculumGenerator.GeneratedOutline outline,
        String journeyId,
        String status,
        Instant timestamp) {
}
