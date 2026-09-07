package com.example.agent.persistence;

import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.genai.JsonSerializable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

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

  public static PersistedEvent from(String sessionId, String runId, Event event) {
    String toolCall =
        event.functionCalls().isEmpty()
            ? null
            : JsonSerializable.toJsonString(event.functionCalls());
    String toolResult =
        event.functionResponses().isEmpty()
            ? null
            : JsonSerializable.toJsonString(event.functionResponses());
    String eventType =
        !event.functionCalls().isEmpty()
            ? "tool_call"
            : !event.functionResponses().isEmpty()
                ? "tool_result"
                : event.errorMessage().isPresent() ? "error" : "message";
    return new PersistedEvent(
        event.id(),
        sessionId,
        runId,
        event.author() == null ? "unknown" : event.author(),
        eventType,
        event.stringifyContent(),
        toolCall,
        toolResult,
        Instant.ofEpochMilli(event.timestamp()),
        rawJson(event, toolCall, toolResult));
  }

  private static String rawJson(Event event, String toolCall, String toolResult) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("id", event.id());
    raw.put("invocationId", event.invocationId());
    raw.put("author", event.author());
    raw.put("content", event.stringifyContent());
    raw.put("functionCalls", toolCall);
    raw.put("functionResponses", toolResult);
    event.partial().ifPresent(value -> raw.put("partial", value));
    event.turnComplete().ifPresent(value -> raw.put("turnComplete", value));
    event.errorCode().ifPresent(value -> raw.put("errorCode", value.toString()));
    event.errorMessage().ifPresent(value -> raw.put("errorMessage", value));
    event.finishReason().ifPresent(value -> raw.put("finishReason", value.toString()));
    raw.put("timestamp", event.timestamp());
    return JsonBaseModel.toJsonString(raw);
  }

  public static PersistedEvent error(String sessionId, String runId, Throwable error) {
    String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    return new PersistedEvent(
        Event.generateEventId(),
        sessionId,
        runId,
        "system",
        "error",
        message,
        null,
        null,
        Instant.now(),
        JsonBaseModel.toJsonString(Map.of("errorMessage", message)));
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
    return JsonBaseModel.toJsonString(payload);
  }
}
