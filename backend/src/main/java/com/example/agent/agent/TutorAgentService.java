package com.example.agent.agent;

import com.example.agent.config.DatabaseTransferCoordinator;
import com.example.agent.persistence.AgentStatePersistenceException;
import com.example.agent.persistence.MessageRecord;
import com.example.agent.persistence.RunRecord;
import com.example.agent.persistence.SessionRecord;
import com.example.agent.persistence.SqliteRepository;
import com.example.agent.persistence.TutorEvent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
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

/** 运行唯一的 AgentScope TutorAgent，并将框架无关事件投影到 SQLite 和 SSE。 */
@Service
public class TutorAgentService {

    private final HarnessAgent tutorAgent;
    private final SqliteRepository repository;
    private final TutorEventStream eventStream;
    private final TutorEventProjector eventProjector;
    private final ExecutorService executor;
    private final AgentStateStore stateStore;
    private final DatabaseTransferCoordinator transferCoordinator;
    private final ConcurrentHashMap<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown;

    public TutorAgentService(
            HarnessAgent tutorAgent,
            SqliteRepository repository,
            EventHub eventHub,
            ExecutorService executor,
            AgentStateStore stateStore) {
        this(tutorAgent, repository, new TutorEventStream(repository, eventHub), executor, stateStore,
                new DatabaseTransferCoordinator());
    }

  @Autowired
  public TutorAgentService(
          HarnessAgent tutorAgent,
          SqliteRepository repository,
          TutorEventStream eventStream,
          ExecutorService executor,
          AgentStateStore stateStore,
          DatabaseTransferCoordinator transferCoordinator) {
        this.tutorAgent = tutorAgent;
        this.repository = repository;
      this.eventStream = eventStream;
      this.eventProjector = new TutorEventProjector();
        this.executor = executor;
        this.stateStore = stateStore;
      this.transferCoordinator = transferCoordinator;
    }

  /** 启动 TutorAgent 调用前，确认会话已经持久化存在。 */
    public void ensureSession(SessionRecord session) {
        repository.findSession(session.id())
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + session.id()));
    }

  /**
   * 启动一次调用，并一直保留 Agent lease，直到终态事件完成持久化。
   *
   * <p>这样可以避免数据库替换与最终消息、事件和 AgentState 写入发生竞争；导入路径会拒绝获取
   * lease，而不是取消提供商流。</p>
   */
    public RunReceipt start(SessionRecord session, String content) {
        return start(session, content, content);
    }

    /** 启动一次调用；agentContent 可携带本次页面题面，但不会写入用户消息记录。 */
    public RunReceipt start(SessionRecord session, String content, String agentContent) {
        if (shuttingDown) throw new IllegalStateException("agent_unavailable");
        String input = agentContent == null || agentContent.isBlank() ? content : agentContent;
      DatabaseTransferCoordinator.Lease agentLease = transferCoordinator.beginAgentRun();
      try {
        ensureSession(session);
          return startWithLease(session, content, input, agentLease);
      } catch (RuntimeException error) {
        agentLease.close();
        throw error;
      }
    }

  private RunReceipt startWithLease(
          SessionRecord session, String content, String agentContent, DatabaseTransferCoordinator.Lease agentLease) {
        String messageId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
    boolean handedOff = false;
    try {
      try {
        ensureRuntimeState(session);
      } catch (RuntimeException error) {
        recordPreStartFailure(session, runId, now, error);
        throw error;
      }
      repository.insertMessage(new MessageRecord(messageId, session.id(), "user", content, now));
      repository.insertRun(new RunRecord(runId, session.id(), "RUNNING", null, now, null));
      ActiveRun run = new ActiveRun(runId, session.id(), agentLease);
      activeRuns.put(runId, run);
      handedOff = true;
      try {
          executor.submit(() -> execute(session, run, agentContent));
      } catch (RejectedExecutionException error) {
        finishFailed(run, error);
      }
      return new RunReceipt(runId, messageId);
    } finally {
      if (!handedOff) agentLease.close();
    }
    }

    public boolean active(String sessionId) {
        return activeRuns.values().stream().anyMatch(run -> run.sessionId.equals(sessionId));
    }

  /** 取消用户请求的运行；释放订阅会继续传递到 AgentScope 和提供商订阅。 */
    public boolean cancel(String sessionId, String runId, String reason) {
        ActiveRun run = activeRuns.get(runId);
        if (run == null || !run.sessionId.equals(sessionId) || !run.requestCancellation(reason)) return false;
        run.dispose();
        finishCancelled(run);
        return true;
    }

  /** 会话的最后一个 SSE 客户端断开时，取消该会话的活动任务。 */
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
            eventProjector.project(session.id(), run.runId, event).ifPresent(eventStream::publish);
        }
    }

    private void recordPreStartFailure(
            SessionRecord session, String runId, Instant startedAt, Throwable error) {
        repository.insertRun(new RunRecord(runId, session.id(), "RUNNING", null, startedAt, null));
        TutorEvent persisted = eventStream.publish(TutorEvent.error(session.id(), runId, error));
        repository.finishRun(runId, "FAILED", persisted.content(), Instant.now());
    }

    private void finishCompleted(ActiveRun run) {
        synchronized (run) {
            if (run.terminal) return;
            if (run.cancelRequested) {
                finishCancelledLocked(run);
                return;
            }
            try {
                String finalText = eventProjector.takeResponse(run.runId);
                if (!finalText.isBlank()) {
                    repository.insertMessage(new MessageRecord(
                            UUID.randomUUID().toString(), run.sessionId, "assistant", finalText, Instant.now()));
                }
                try {
                    eventStream.publish(TutorEvent.complete(run.sessionId, run.runId, Instant.now()));
                    markTerminal(run);
                    repository.finishRun(run.runId, "COMPLETED", null, Instant.now());
                } finally {
                    run.agentLease.close();
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
        try {
            TutorEvent persisted = eventStream.publish(TutorEvent.error(run.sessionId, run.runId, error));
            markTerminal(run);
            repository.finishRun(run.runId, "FAILED", persisted.content(), Instant.now());
        } finally {
            run.agentLease.close();
        }
    }

    private void finishCancelled(ActiveRun run) {
        synchronized (run) {
            if (run.terminal) return;
            finishCancelledLocked(run);
        }
    }

    private void finishCancelledLocked(ActiveRun run) {
        try {
            TutorEvent persisted = eventStream.publish(
                    TutorEvent.cancelled(run.sessionId, run.runId, run.cancellationReason, Instant.now()));
            markTerminal(run);
            repository.finishRun(run.runId, "CANCELLED", run.cancellationReason, Instant.now());
        } finally {
            run.agentLease.close();
        }
    }

    private void markTerminal(ActiveRun run) {
        run.terminal = true;
        activeRuns.remove(run.runId, run);
        eventProjector.clear(run.runId);
    }

    private static final class ActiveRun {

        private final String runId;
        private final String sessionId;
        private final DatabaseTransferCoordinator.Lease agentLease;
        private Disposable subscription;
        private boolean cancelRequested;
        private String cancellationReason = "cancelled";
        private boolean terminal;

        private ActiveRun(String runId, String sessionId, DatabaseTransferCoordinator.Lease agentLease) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.agentLease = agentLease;
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
