package com.example.agent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Framework-neutral SQLite projection and SSE payload for one Tutor event. */
public record PersistedEvent(
        String id,
        String sessionId,
        String runId,
        String author,
        String eventType,
        String content,
        String toolCall,
        String toolResult,
        Instant timestamp,
        String rawJson) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static PersistedEvent message(
            String sessionId, String runId, String author, String content, Instant timestamp) {
        return create(sessionId, runId, author, "message", content, null, null, timestamp);
    }

    public static PersistedEvent toolCall(
            String sessionId, String runId, String author, String content, String toolCall, Instant timestamp) {
        return create(sessionId, runId, author, "tool_call", content, toolCall, null, timestamp);
    }

    public static PersistedEvent toolResult(
            String sessionId, String runId, String author, String content, String toolResult, Instant timestamp) {
        return create(sessionId, runId, author, "tool_result", content, null, toolResult, timestamp);
    }

    public static PersistedEvent error(String sessionId, String runId, Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return create(sessionId, runId, "system", "error", message, null, null, Instant.now());
    }

    public static PersistedEvent complete(String sessionId, String runId, Instant timestamp) {
        return create(sessionId, runId, "system", "complete", "", null, null, timestamp);
    }

    private static PersistedEvent create(
            String sessionId,
            String runId,
            String author,
            String eventType,
            String content,
            String toolCall,
            String toolResult,
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
        raw.put("timestamp", timestamp.toString());
        return new PersistedEvent(
                id, sessionId, runId, author, eventType, content == null ? "" : content,
                toolCall, toolResult, timestamp, write(raw));
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
