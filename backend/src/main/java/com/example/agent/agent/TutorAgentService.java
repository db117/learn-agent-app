package com.example.agent.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.example.agent.learning.tutor.TutorContextService;
import com.example.agent.persistence.MessageRecord;
import com.example.agent.persistence.PersistedEvent;
import com.example.agent.persistence.RunRecord;
import com.example.agent.persistence.SessionRecord;
import com.example.agent.persistence.SqliteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/** Runs the single SAA TutorAgent and projects its framework-neutral events to SQLite/SSE. */
@Service
public class TutorAgentService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReactAgent tutorAgent;
    private final TutorContextService context;
    private final SqliteRepository repository;
    private final EventHub eventHub;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, Boolean> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StringBuilder> responseText = new ConcurrentHashMap<>();

    public TutorAgentService(
            ReactAgent tutorAgent,
            TutorContextService context,
            SqliteRepository repository,
            EventHub eventHub,
            ExecutorService executor) {
        this.tutorAgent = tutorAgent;
        this.context = context;
        this.repository = repository;
        this.eventHub = eventHub;
        this.executor = executor;
    }

    /** SAA uses the durable message history as its session state; no in-memory session restore is needed. */
    public void ensureSession(SessionRecord session) {
        repository.findSession(session.id())
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + session.id()));
    }

    public RunReceipt start(SessionRecord session, String content) {
        ensureSession(session);
        String messageId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        repository.insertMessage(new MessageRecord(messageId, session.id(), "user", content, now));
        repository.insertRun(new RunRecord(runId, session.id(), "RUNNING", null, now, null));
        activeSessions.put(session.id(), Boolean.TRUE);
        executor.submit(() -> execute(session, runId));
        return new RunReceipt(runId, messageId);
    }

    public boolean active(String sessionId) {
        return activeSessions.containsKey(sessionId);
    }

    private void execute(SessionRecord session, String runId) {
        try {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(context.forSession(session.id())));
            for (MessageRecord message : repository.listMessages(session.id())) {
                messages.add("user".equals(message.role())
                        ? new UserMessage(message.content())
                        : new AssistantMessage(message.content()));
            }
            tutorAgent.streamMessages(
                            messages,
                            RunnableConfig.builder().threadId(session.id()).build())
                    .doOnNext(message -> persistMessage(session, runId, message))
                    .doOnComplete(() -> finishCompleted(session.id(), runId))
                    .blockLast();
        } catch (Throwable error) {
            finishFailed(session.id(), runId, error);
        }
    }

    private void persistMessage(SessionRecord session, String runId, Message message) {
        Instant timestamp = Instant.now();
        if (message instanceof AssistantMessage assistant) {
            if (!assistant.getToolCalls().isEmpty()) {
                publish(PersistedEvent.toolCall(
                        session.id(), runId, "tutor_agent", assistant.getText(), toolCalls(assistant), timestamp));
                return;
            }
            String text = assistant.getText() == null ? "" : assistant.getText();
            if (!text.isBlank()) responseText.computeIfAbsent(runId, ignored -> new StringBuilder()).append(text);
            publish(PersistedEvent.message(session.id(), runId, "tutor_agent", text, timestamp));
            return;
        }
        if (message instanceof ToolResponseMessage tool) {
            publish(PersistedEvent.toolResult(
                    session.id(), runId, "tool", toolText(tool), toolResults(tool), timestamp));
        }
    }

    private void publish(PersistedEvent event) {
        repository.insertEvent(event);
        eventHub.publish(event);
    }

    private String toolCalls(AssistantMessage assistant) {
        return write(assistant.getToolCalls().stream().map(call -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", call.id());
            value.put("name", call.name());
            value.put("arguments", call.arguments());
            return value;
        }).toList());
    }

    private String toolResults(ToolResponseMessage message) {
        return write(message.getResponses().stream().map(response -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", response.id());
            value.put("name", response.name());
            value.put("response", response.responseData());
            return value;
        }).toList());
    }

    private String toolText(ToolResponseMessage message) {
        return message.getResponses().stream().map(ToolResponseMessage.ToolResponse::responseData)
                .reduce("", (left, right) -> left + right);
    }

    private String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize Tutor tool event", error);
        }
    }

    private void finishCompleted(String sessionId, String runId) {
        StringBuilder accumulated = responseText.remove(runId);
        String finalText = accumulated == null ? "" : accumulated.toString();
        if (!finalText.isBlank()) {
            repository.insertMessage(new MessageRecord(
                    UUID.randomUUID().toString(), sessionId, "assistant", finalText, Instant.now()));
        }
        publish(PersistedEvent.complete(sessionId, runId, Instant.now()));
        repository.finishRun(runId, "COMPLETED", null, Instant.now());
        activeSessions.remove(sessionId);
    }

    private void finishFailed(String sessionId, String runId, Throwable error) {
        PersistedEvent persisted = PersistedEvent.error(sessionId, runId, error);
        publish(persisted);
        repository.finishRun(runId, "FAILED", persisted.content(), Instant.now());
        responseText.remove(runId);
        activeSessions.remove(sessionId);
    }

    public record RunReceipt(String runId, String messageId) {
    }
}
