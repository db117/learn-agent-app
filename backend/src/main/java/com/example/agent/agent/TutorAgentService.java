package com.example.agent.agent;

import com.example.agent.persistence.MessageRecord;
import com.example.agent.persistence.RunRecord;
import com.example.agent.persistence.SessionRecord;
import com.example.agent.persistence.SqliteRepository;
import com.example.agent.persistence.TutorEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
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

    private static final String SKILL_LOAD_TOOL = "load_skill_through_path";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HarnessAgent tutorAgent;
    private final SqliteRepository repository;
    private final EventHub eventHub;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, Boolean> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StringBuilder> responseText = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> skillNames = new ConcurrentHashMap<>();

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
        if (event instanceof ThinkingBlockStartEvent) {
            publish(TutorEvent.reasoningSummary(session.id(), runId, timestamp));
            return;
        }
        if (event instanceof TextBlockDeltaEvent textDelta) {
            String text = textDelta.getDelta() == null ? "" : textDelta.getDelta();
            if (!text.isEmpty()) responseText.computeIfAbsent(runId, ignored -> new StringBuilder()).append(text);
            publish(TutorEvent.textDelta(session.id(), runId, "tutor_agent", text, timestamp));
            return;
        }
        if (event instanceof ToolCallStartEvent toolCall) {
            if (isSkillLoad(toolCall.getToolCallName())) {
                publish(TutorEvent.skillLoadStart(session.id(), runId, timestamp));
                return;
            }
            publish(TutorEvent.toolCall(session.id(), runId, "tutor_agent", toolCall.getToolCallName(), timestamp));
            return;
        }
        if (event instanceof ToolCallDeltaEvent toolCall && isSkillLoad(toolCall.getToolCallName())) {
            String skillName = safeSkillName(toolCall.getDelta());
            if (skillName != null) skillNames.put(skillKey(runId, toolCall.getToolCallId()), skillName);
            return;
        }
        if (event instanceof ToolResultEndEvent toolResult && isSkillLoad(toolResult.getToolCallName())) {
            String skillName = skillNames.remove(skillKey(runId, toolResult.getToolCallId()));
            if (toolResult.getState() != ToolResultState.SUCCESS) {
                throw new IllegalStateException("AgentScope Skill load failed");
            }
            publish(TutorEvent.skillLoadComplete(session.id(), runId, skillName, timestamp));
            return;
        }
        if (event instanceof ToolResultTextDeltaEvent toolResult) {
            if (isSkillLoad(toolResult.getToolCallName())) return;
            publish(TutorEvent.toolResult(session.id(), runId, "tool", toolResult.getDelta(), timestamp));
        }
    }

    private static boolean isSkillLoad(String toolName) {
        return SKILL_LOAD_TOOL.equals(toolName);
    }

    private static String skillKey(String runId, String toolCallId) {
        return runId + ":" + toolCallId;
    }

    private static String safeSkillName(String delta) {
        if (delta == null || delta.isBlank()) return null;
        try {
            JsonNode payload = JSON.readTree(delta);
            JsonNode value = payload.get("skillId");
            if (value == null) value = payload.get("skill_id");
            if (value != null && value.isTextual() && value.textValue().matches("[A-Za-z0-9._-]{1,120}")) {
                return value.textValue();
            }
        } catch (Exception ignored) {
            // Streaming tool arguments can arrive as incomplete JSON; the UI can use the generic label.
        }
        return null;
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
        skillNames.keySet().removeIf(key -> key.startsWith(runId + ":"));
        activeSessions.remove(sessionId);
    }

    private void finishFailed(String sessionId, String runId, Throwable error) {
        TutorEvent persisted = TutorEvent.error(sessionId, runId, error);
        publish(persisted);
        repository.finishRun(runId, "FAILED", persisted.content(), Instant.now());
        responseText.remove(runId);
        skillNames.keySet().removeIf(key -> key.startsWith(runId + ":"));
        activeSessions.remove(sessionId);
    }

    public record RunReceipt(String runId, String messageId) {
    }
}
