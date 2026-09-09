package com.example.agent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Framework-neutral event projected for SQLite replay and the Tutor SSE stream. */
public record TutorEvent(
        String id,
        String sessionId,
        String runId,
        String author,
        String eventType,
        String content,
        String toolCall,
        String toolResult,
        String skillName,
        String summary,
        String status,
        Instant timestamp,
        String rawJson) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static TutorEvent textDelta(
            String sessionId, String runId, String author, String content, Instant timestamp) {
        return create(sessionId, runId, author, "text_delta", content, null, null,
                null, null, null, timestamp);
    }

    public static TutorEvent toolCall(
            String sessionId, String runId, String author, String content, Instant timestamp) {
        return create(sessionId, runId, author, "tool_call", content, content, null,
                null, null, null, timestamp);
    }

    public static TutorEvent toolResult(
            String sessionId, String runId, String author, String content, Instant timestamp) {
        return create(sessionId, runId, author, "tool_result", content, null, content,
                null, null, null, timestamp);
    }

    public static TutorEvent reasoningSummary(String sessionId, String runId, Instant timestamp) {
        String summary = "正在分析问题并规划回答";
        return create(sessionId, runId, "tutor_agent", "reasoning_summary", summary,
                null, null, null, summary, "started", timestamp);
    }

    public static TutorEvent skillLoadStart(String sessionId, String runId, Instant timestamp) {
        return create(sessionId, runId, "tutor_agent", "skill_load_start", "正在加载工程 Skill",
                null, null, null, null, "started", timestamp);
    }

    public static TutorEvent skillLoadComplete(
            String sessionId, String runId, String skillName, Instant timestamp) {
        return create(sessionId, runId, "tutor_agent", "skill_load_complete", "Skill 已加载",
                null, null, skillName, null, "completed", timestamp);
    }

    public static TutorEvent error(String sessionId, String runId, Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return create(sessionId, runId, "system", "error", message, null, null,
                null, null, "failed", Instant.now());
    }

    public static TutorEvent complete(String sessionId, String runId, Instant timestamp) {
        return create(sessionId, runId, "system", "complete", "", null, null,
                null, null, "completed", timestamp);
    }

    private static TutorEvent create(
            String sessionId,
            String runId,
            String author,
            String eventType,
            String content,
            String toolCall,
            String toolResult,
            String skillName,
            String summary,
            String status,
            Instant timestamp) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", id);
        raw.put("sessionId", sessionId);
        raw.put("runId", runId);
        raw.put("author", author);
        raw.put("eventType", eventType);
        raw.put("content", content == null ? "" : content);
        raw.put("toolCall", toolCall);
        raw.put("toolResult", toolResult);
        raw.put("skillName", skillName);
        raw.put("summary", summary);
        raw.put("status", status);
        raw.put("timestamp", timestamp.toString());
        return new TutorEvent(
                id, sessionId, runId, author, eventType, content == null ? "" : content,
                toolCall, toolResult, skillName, summary, status, timestamp, write(raw));
    }

    public String json() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("sessionId", sessionId);
        payload.put("runId", runId);
        payload.put("author", author);
        payload.put("eventType", eventType);
        payload.put("content", content);
        payload.put("toolCall", toolCall);
        payload.put("toolResult", toolResult);
        payload.put("skillName", skillName);
        payload.put("summary", summary);
        payload.put("status", status);
        payload.put("timestamp", timestamp.toString());
        payload.put("rawJson", rawJson);
        return write(payload);
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize Tutor event", error);
        }
    }
}
