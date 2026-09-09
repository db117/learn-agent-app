package com.example.agent.agent;

import com.example.agent.persistence.MessageRecord;
import com.example.agent.persistence.RunRecord;
import com.example.agent.persistence.SessionRecord;
import com.example.agent.persistence.SqliteRepository;
import com.example.agent.persistence.TutorEvent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/** Runs the single AgentScope TutorAgent and projects framework-neutral events to SQLite/SSE. */
@Service
public class TutorAgentService {

    private final HarnessAgent tutorAgent;
    private final SqliteRepository repository;
    private final EventHub eventHub;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, Boolean> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StringBuilder> responseText = new ConcurrentHashMap<>();

    public TutorAgentService(
            HarnessAgent tutorAgent,
            SqliteRepository repository,
            EventHub eventHub,
            ExecutorService executor) {
        this.tutorAgent = tutorAgent;
        this.repository = repository;
        this.eventHub = eventHub;
        this.executor = executor;
    }

    /** The durable application message history is the input context for each short AgentScope call. */
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
            List<Msg> messages = new ArrayList<>();
            for (MessageRecord message : repository.listMessages(session.id())) {
                messages.add("user".equals(message.role())
                        ? new UserMessage(message.content())
                        : new AssistantMessage(message.content()));
            }
            RuntimeContext runtimeContext = RuntimeContext.builder()
                    .userId(session.userId())
                    .sessionId(session.id())
                    .build();
            tutorAgent.streamEvents(messages, runtimeContext)
                    .doOnNext(event -> persistEvent(session, runId, event))
                    .doOnComplete(() -> finishCompleted(session.id(), runId))
                    .blockLast();
        } catch (Throwable error) {
            finishFailed(session.id(), runId, error);
        }
    }

    private void persistEvent(SessionRecord session, String runId, AgentEvent event) {
        Instant timestamp = Instant.now();
        if (event instanceof TextBlockDeltaEvent textDelta) {
            String text = textDelta.getDelta() == null ? "" : textDelta.getDelta();
            if (!text.isEmpty()) responseText.computeIfAbsent(runId, ignored -> new StringBuilder()).append(text);
            publish(TutorEvent.textDelta(session.id(), runId, "tutor_agent", text, timestamp));
            return;
        }
        if (event instanceof ToolCallStartEvent toolCall) {
            publish(TutorEvent.toolCall(session.id(), runId, "tutor_agent", toolCall.getToolCallName(), timestamp));
            return;
        }
        if (event instanceof ToolResultTextDeltaEvent toolResult) {
            publish(TutorEvent.toolResult(session.id(), runId, "tool", toolResult.getDelta(), timestamp));
        }
    }

    private void publish(TutorEvent event) {
        repository.insertEvent(event);
        eventHub.publish(event);
    }

    private void finishCompleted(String sessionId, String runId) {
        StringBuilder accumulated = responseText.remove(runId);
        String finalText = accumulated == null ? "" : accumulated.toString();
        if (!finalText.isBlank()) {
            repository.insertMessage(new MessageRecord(
                    UUID.randomUUID().toString(), sessionId, "assistant", finalText, Instant.now()));
        }
        publish(TutorEvent.complete(sessionId, runId, Instant.now()));
        repository.finishRun(runId, "COMPLETED", null, Instant.now());
        activeSessions.remove(sessionId);
    }

    private void finishFailed(String sessionId, String runId, Throwable error) {
        TutorEvent persisted = TutorEvent.error(sessionId, runId, error);
        publish(persisted);
        repository.finishRun(runId, "FAILED", persisted.content(), Instant.now());
        responseText.remove(runId);
        activeSessions.remove(sessionId);
    }

    public record RunReceipt(String runId, String messageId) {
    }
}
