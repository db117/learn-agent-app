package com.example.agent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/** Framework-neutral event projected for SQLite replay and the Tutor SSE stream. */
public record TutorEvent(
        long sequence,
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
        @JsonIgnore
        String rawJson) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static TutorEvent textDelta(
            String sessionId, String runId, String author, String content, Instant timestamp) {
        return create(sessionId, runId, author, "text_delta", content, null, null,
                null, null, null, timestamp);
    }

    public static TutorEvent toolCall(
            String sessionId, String runId, String author, String content, Instant timestamp) {
        String toolName = safeToken(content);
        return create(sessionId, runId, author, "tool_call", toolName, toolName, null,
                null, null, null, timestamp);
    }

    public static TutorEvent toolResult(
            String sessionId, String runId, String author, String content, Instant timestamp) {
        String toolName = safeToken(content);
        return create(sessionId, runId, author, "tool_result", "工具结果已更新", null, toolName,
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
        String code = errorCode(error);
        return error(sessionId, runId, code, Instant.now());
    }

    public static TutorEvent error(String sessionId, String runId, String code, Instant timestamp) {
        String normalized = safeToken(code).toLowerCase(Locale.ROOT);
        String message = switch (normalized) {
            case "skill_load_failed" -> "AgentScope Skill load failed";
            case "agent_state_restore_failed" -> "AgentState restore failed";
            case "llm_unavailable" -> "LLM is unavailable";
            case "provider_error" -> "Model provider failed";
            case "structure_validation_failed" -> "Generated response failed validation";
            default -> "Tutor call failed";
        };
        return create(sessionId, runId, "system", "error", message, null, null,
                null, normalized, "failed", timestamp);
    }

    public static TutorEvent cancelled(
            String sessionId, String runId, String reason, Instant timestamp) {
        return create(sessionId, runId, "system", "cancelled", "调用已取消", null, null,
                null, safeToken(reason).toLowerCase(Locale.ROOT), "cancelled", timestamp);
    }

    public static String errorCode(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof AgentStatePersistenceException) return "agent_state_restore_failed";
            if (current instanceof CancellationException || current instanceof InterruptedException) {
                return "cancelled";
            }
            current = current.getCause();
        }
        String message = error == null ? "" : String.valueOf(error.getMessage());
        if (message.contains("AgentScope Skill load failed")) return "skill_load_failed";
        if (message.contains("LLM is not configured")) return "llm_unavailable";
        String type = error == null ? "" : error.getClass().getName();
        if (type.contains("agentscope") || type.contains("OpenAI") || type.contains("Provider")) {
            return "provider_error";
        }
        return "agent_error";
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
                0L, id, sessionId, runId, author, eventType, content == null ? "" : content,
                toolCall, toolResult, skillName, summary, status, timestamp, write(raw));
    }

    public TutorEvent withSequence(long sequence) {
        if (sequence <= 0) throw new IllegalArgumentException("event sequence must be positive");
        return new TutorEvent(sequence, id, sessionId, runId, author, eventType, content,
                toolCall, toolResult, skillName, summary, status, timestamp, rawJson);
    }

    public String json() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sequence", sequence);
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
        return write(payload);
    }

    private static String safeToken(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,120}") ? value : "unknown";
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize Tutor event", error);
        }
    }
}
