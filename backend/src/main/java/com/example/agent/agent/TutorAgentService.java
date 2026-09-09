package com.example.agent.agent;

import com.example.agent.persistence.AgentStatePersistenceException;
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
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** Runs the single AgentScope TutorAgent and projects framework-neutral events to SQLite/SSE. */
@Service
public class TutorAgentService {

    private static final String SKILL_LOAD_TOOL = "load_skill_through_path";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HarnessAgent tutorAgent;
    private final SqliteRepository repository;
    private final EventHub eventHub;
    private final ExecutorService executor;
    private final AgentStateStore stateStore;
    private final ConcurrentHashMap<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StringBuilder> responseText = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> skillNames = new ConcurrentHashMap<>();
    private final Object eventOrderLock = new Object();
    private volatile boolean shuttingDown;

    public TutorAgentService(
            HarnessAgent tutorAgent,
            SqliteRepository repository,
            EventHub eventHub,
            ExecutorService executor,
            AgentStateStore stateStore) {
        this.tutorAgent = tutorAgent;
        this.repository = repository;
        this.eventHub = eventHub;
        this.executor = executor;
        this.stateStore = stateStore;
    }

    /** Verify the durable session exists before starting a TutorAgent call. */
    public void ensureSession(SessionRecord session) {
        repository.findSession(session.id())
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + session.id()));
    }

    /** Start a call; AgentState owns the runtime conversation while messages remain inspectable. */
    public RunReceipt start(SessionRecord session, String content) {
        if (shuttingDown) throw new IllegalStateException("agent_unavailable");
        ensureSession(session);
        String messageId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        try {
            ensureRuntimeState(session);
        } catch (RuntimeException error) {
            recordPreStartFailure(session, runId, now, error);
            throw error;
        }
        repository.insertMessage(new MessageRecord(messageId, session.id(), "user", content, now));
        repository.insertRun(new RunRecord(runId, session.id(), "RUNNING", null, now, null));
        ActiveRun run = new ActiveRun(runId, session.id());
        activeRuns.put(runId, run);
        try {
            executor.submit(() -> execute(session, run, content));
        } catch (RejectedExecutionException error) {
            finishFailed(run, error);
        }
        return new RunReceipt(runId, messageId);
    }

    public boolean active(String sessionId) {
        return activeRuns.values().stream().anyMatch(run -> run.sessionId.equals(sessionId));
    }

    /** Cancel a user-requested run; disposal reaches the AgentScope and provider subscriptions. */
    public boolean cancel(String sessionId, String runId, String reason) {
        ActiveRun run = activeRuns.get(runId);
        if (run == null || !run.sessionId.equals(sessionId) || !run.requestCancellation(reason)) return false;
        run.dispose();
        finishCancelled(run);
        return true;
    }

    /** Cancel active work when the last SSE client for a session disconnects. */
    public void cancelForClientDisconnect(String sessionId) {
        activeRuns.values().stream()
                .filter(run -> run.sessionId.equals(sessionId))
                .toList()
                .forEach(run -> {
                    if (!run.requestCancellation("client_disconnected")) return;
                    run.dispose();
                    try {
                        executor.submit(() -> finishCancelled(run));
                    } catch (RejectedExecutionException error) {
                        finishCancelled(run);
                    }
                });
    }

    @PreDestroy
    void shutdown() {
        shuttingDown = true;
        activeRuns.values().stream().toList().forEach(run -> {
            if (!run.requestCancellation("backend_shutdown")) return;
            run.dispose();
            finishCancelled(run);
        });
    }

    private void execute(SessionRecord session, ActiveRun run, String content) {
        if (run.cancelRequestedOrTerminal()) return;
        try {
            RuntimeContext runtimeContext = RuntimeContext.builder()
                    .userId(session.userId())
                    .sessionId(session.id())
                    .build();
            Disposable subscription = tutorAgent.streamEvents(List.of(new UserMessage(content)), runtimeContext)
                    .publishOn(Schedulers.boundedElastic())
                    .subscribe(
                            event -> persistEvent(session, run, event),
                            error -> finishFailed(run, error),
                            () -> finishCompleted(run));
            run.attach(subscription);
        } catch (Throwable error) {
            finishFailed(run, error);
        }
    }

    private void ensureRuntimeState(SessionRecord session) {
        try {
            if (!stateStore.exists(session.userId(), session.id())) {
                if (!repository.listMessages(session.id()).isEmpty()) {
                    throw missingState(session);
                }
                return;
            }
            AgentState state = stateStore.get(session.userId(), session.id(), "agent_state", AgentState.class)
                    .orElseThrow(() -> missingState(session));
            if (!session.id().equals(state.getSessionId())
                    || !Objects.equals(session.userId(), state.getUserId())) {
                throw missingState(session);
            }
        } catch (AgentStatePersistenceException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new AgentStatePersistenceException(
                    "AgentState restore failed for session " + session.id()
                            + "; refusing to create an empty runtime context", error);
        }
    }

    private static AgentStatePersistenceException missingState(SessionRecord session) {
        return new AgentStatePersistenceException(
                "AgentState restore failed for session " + session.id()
                        + "; refusing to create an empty runtime context");
    }

    private void persistEvent(SessionRecord session, ActiveRun run, AgentEvent event) {
        synchronized (run) {
            if (run.cancelRequested || run.terminal) return;
            String runId = run.runId;
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
                publish(TutorEvent.toolResult(session.id(), runId, "tool", toolResult.getToolCallName(), timestamp));
            }
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
        synchronized (eventOrderLock) {
            eventHub.publish(repository.insertEvent(event));
        }
    }

    private void recordPreStartFailure(
            SessionRecord session, String runId, Instant startedAt, Throwable error) {
        repository.insertRun(new RunRecord(runId, session.id(), "RUNNING", null, startedAt, null));
        synchronized (eventOrderLock) {
            TutorEvent persisted = repository.insertEvent(TutorEvent.error(session.id(), runId, error));
            eventHub.publish(persisted);
            repository.finishRun(runId, "FAILED", persisted.content(), Instant.now());
        }
    }

    private void finishCompleted(ActiveRun run) {
        synchronized (run) {
            if (run.terminal) return;
            if (run.cancelRequested) {
                finishCancelledLocked(run);
                return;
            }
            try {
                StringBuilder accumulated = responseText.get(run.runId);
                String finalText = accumulated == null ? "" : accumulated.toString();
                if (!finalText.isBlank()) {
                    repository.insertMessage(new MessageRecord(
                            UUID.randomUUID().toString(), run.sessionId, "assistant", finalText, Instant.now()));
                }
                synchronized (eventOrderLock) {
                    TutorEvent complete = repository.insertEvent(
                            TutorEvent.complete(run.sessionId, run.runId, Instant.now()));
                    markTerminal(run);
                    eventHub.publish(complete);
                    repository.finishRun(run.runId, "COMPLETED", null, Instant.now());
                }
            } catch (Throwable error) {
                if (!run.terminal) finishFailedLocked(run, error);
            }
        }
    }

    private void finishFailed(ActiveRun run, Throwable error) {
        synchronized (run) {
            if (run.terminal) return;
            if (run.cancelRequested || "cancelled".equals(TutorEvent.errorCode(error))) {
                if (!run.cancelRequested) run.requestCancellation("agent_cancelled");
                finishCancelledLocked(run);
                return;
            }
            finishFailedLocked(run, error);
        }
    }

    private void finishFailedLocked(ActiveRun run, Throwable error) {
        synchronized (eventOrderLock) {
            TutorEvent persisted = repository.insertEvent(
                    TutorEvent.error(run.sessionId, run.runId, error));
            markTerminal(run);
            eventHub.publish(persisted);
            repository.finishRun(run.runId, "FAILED", persisted.content(), Instant.now());
        }
    }

    private void finishCancelled(ActiveRun run) {
        synchronized (run) {
            if (run.terminal) return;
            finishCancelledLocked(run);
        }
    }

    private void finishCancelledLocked(ActiveRun run) {
        synchronized (eventOrderLock) {
            TutorEvent persisted = repository.insertEvent(
                    TutorEvent.cancelled(run.sessionId, run.runId, run.cancellationReason, Instant.now()));
            markTerminal(run);
            eventHub.publish(persisted);
            repository.finishRun(run.runId, "CANCELLED", run.cancellationReason, Instant.now());
        }
    }

    private void markTerminal(ActiveRun run) {
        run.terminal = true;
        activeRuns.remove(run.runId, run);
        responseText.remove(run.runId);
        skillNames.keySet().removeIf(key -> key.startsWith(run.runId + ":"));
    }

    private static final class ActiveRun {

        private final String runId;
        private final String sessionId;
        private Disposable subscription;
        private boolean cancelRequested;
        private String cancellationReason = "cancelled";
        private boolean terminal;

        private ActiveRun(String runId, String sessionId) {
            this.runId = runId;
            this.sessionId = sessionId;
        }

        private synchronized boolean requestCancellation(String reason) {
            if (terminal || cancelRequested) return false;
            cancelRequested = true;
            cancellationReason = reason == null || reason.isBlank() ? "cancelled" : reason;
            return true;
        }

        private synchronized boolean cancelRequestedOrTerminal() {
            return cancelRequested || terminal;
        }

        private synchronized void attach(Disposable subscription) {
            this.subscription = subscription;
            if (cancelRequested || terminal) subscription.dispose();
        }

        private synchronized void dispose() {
            if (subscription != null) subscription.dispose();
        }
    }

    public record RunReceipt(String runId, String messageId) {
    }
}
