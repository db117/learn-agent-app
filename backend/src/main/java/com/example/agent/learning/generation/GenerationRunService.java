package com.example.agent.learning.generation;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

/** 当前 JVM 内模型辅助工作的生命周期、去重和回放边界。 */
@Service
public final class GenerationRunService {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(5);

    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final Map<String, Run> activeByTarget = new ConcurrentHashMap<>();
    private final Duration timeout;
    private final Duration heartbeat;

    @Autowired
    public GenerationRunService(ExecutorService executor) {
        this(executor, DEFAULT_TIMEOUT, DEFAULT_HEARTBEAT);
    }

    GenerationRunService(ExecutorService executor, Duration timeout, Duration heartbeat) {
        this.executor = executor;
        this.timeout = timeout;
        this.heartbeat = heartbeat;
    }

    public StartResult start(GenerationOperation operation, String targetKey, Consumer<Run> work) {
        if (operation == null) throw new IllegalArgumentException("operation is required");
        if (targetKey == null || targetKey.isBlank()) throw new IllegalArgumentException("targetKey must not be blank");
        synchronized (activeByTarget) {
            Run active = activeByTarget.get(targetKey);
            if (active != null && !active.terminal()) return new StartResult(active.id(), false);
            Run run = new Run(UUID.randomUUID().toString(), operation, targetKey);
            runs.put(run.id(), run);
            activeByTarget.put(targetKey, run);
            run.publish("run_started", "PREPARING", "Agent", "已收到请求，正在准备生成。", null, null, null);
            run.schedule(work);
            run.heartbeatTask = scheduler.scheduleAtFixedRate(run::heartbeat,
                    heartbeat.toMillis(), heartbeat.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            return new StartResult(run.id(), true);
        }
    }

    public Flux<GenerationEvent> events(String runId, long lastSequence) {
        return run(runId).events.asFlux().filter(event -> event.sequence() > lastSequence);
    }

    public Flux<GenerationEvent> events(String runId) {
        return events(runId, -1);
    }

    public void cancel(String runId) {
        run(runId).cancel();
    }

    public void withRun(String runId, Consumer<Run> action) {
        action.accept(run(runId));
    }

    private Run run(String runId) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId must not be blank");
        Run run = runs.get(runId);
        if (run == null) throw new IllegalArgumentException("generation run not found: " + runId);
        return run;
    }

    @PreDestroy
    public void close() {
        scheduler.shutdownNow();
    }

    public record StartResult(String runId, boolean created) {
    }

    public final class Run {
        private final String id;
        private final GenerationOperation operation;
        private final String targetKey;
        private final Instant startedAt = Instant.now();
        private final Sinks.Many<GenerationEvent> events = Sinks.many().replay().all();
        private long sequence;
        private String stage = "PREPARING";
        private String status = "RUNNING";
        private boolean terminal;
        private Future<?> activeTask;
        private ScheduledFuture<?> timeoutTask;
        private ScheduledFuture<?> heartbeatTask;

        private Run(String id, GenerationOperation operation, String targetKey) {
            this.id = id;
            this.operation = operation;
            this.targetKey = targetKey;
        }

        public String id() {
            return id;
        }

        public synchronized String stage() {
            return stage;
        }

        public synchronized boolean terminal() {
            return terminal;
        }

        public synchronized void stage(String nextStage) {
            if (terminal || nextStage == null || nextStage.isBlank() || nextStage.equals(stage)) return;
            stage = nextStage;
            refreshTimeout();
            publish("stage_changed", stage, "系统", stageMessage(stage), null, null, null);
        }

        public synchronized void emit(
                String eventType, String nextStage, String author, String content, Object preview) {
            if (terminal) return;
            if (nextStage != null && !nextStage.isBlank()) stage = nextStage;
            refreshTimeout();
            publish(eventType, stage, author, content, preview, null, null);
        }

        /** 将模型的每个文本块作为实时事件发送，不能退化成一次性“模型已开始”状态。 */
        public synchronized void modelText(String content) {
            if (terminal || content == null || content.isBlank()) return;
            emit("model_delta", "CALLING_MODEL", "大模型", content, null);
        }

        public synchronized void complete(String content, Object preview, String resourceType, String resourceId) {
            finish("completed", "COMPLETED", stage, "系统", content, preview, resourceType, resourceId);
        }

        public synchronized void fail(String content) {
            finish("failed", "FAILED", stage, "系统", safeContent(content, "生成失败，请稍后重试。"), null, null, null);
        }

        private synchronized void cancel() {
            if (terminal) return;
            if ("PERSISTING".equals(stage)) {
                throw new IllegalStateException(operation.persistenceMessage());
            }
            finish("cancelled", "CANCELLED", stage, "系统", operation.cancellationMessage(), null, null, null);
            cancelActiveTask();
        }

        private synchronized void schedule(Consumer<Run> work) {
            if (terminal) return;
            if (activeTask != null && !activeTask.isDone()) return;
            refreshTimeout();
            activeTask = executor.submit(() -> {
                try {
                    work.accept(this);
                } catch (RuntimeException error) {
                    fail(operation.failureMessage());
                }
            });
        }

        public void scheduleNext(Consumer<Run> work) {
            schedule(work);
        }

        private void heartbeat() {
            synchronized (this) {
                if (!terminal && !"WAITING_CONFIRMATION".equals(stage) && !"PERSISTING".equals(stage)) {
                    publish("heartbeat", stage, "系统", "仍在处理中。", null, null, null);
                }
            }
        }

        private void timeout() {
            synchronized (this) {
                if (terminal || "PERSISTING".equals(stage) || "WAITING_CONFIRMATION".equals(stage)) return;
                finish("failed", "FAILED", stage, "系统",
                        "生成超时（超过 %d 秒没有新信息），请重试。".formatted(Math.max(1, timeout.toSeconds())),
                        null, null, null);
                cancelActiveTask();
            }
        }

        private void refreshTimeout() {
            if (terminal || "PERSISTING".equals(stage) || "WAITING_CONFIRMATION".equals(stage)) {
                if (timeoutTask != null) timeoutTask.cancel(false);
                return;
            }
            if (timeoutTask != null) timeoutTask.cancel(false);
            timeoutTask = scheduler.schedule(this::timeout, timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        private synchronized void finish(
                String eventType, String nextStatus, String nextStage, String author, String content,
                Object preview, String resourceType, String resourceId) {
            if (terminal) return;
            status = nextStatus;
            if (nextStage != null && !nextStage.isBlank()) stage = nextStage;
            publish(eventType, stage, author, content, preview, resourceType, resourceId);
            terminal = true;
            activeByTarget.remove(targetKey, this);
            if (timeoutTask != null) timeoutTask.cancel(false);
            if (heartbeatTask != null) heartbeatTask.cancel(false);
            events.tryEmitComplete();
        }

        private void publish(
                String eventType, String eventStage, String author, String content, Object preview,
                String resourceType, String resourceId) {
            events.tryEmitNext(new GenerationEvent(
                    ++sequence, id, operation.wireValue(), eventType, eventStage, author,
                    safeContent(content, ""), status, Duration.between(startedAt, Instant.now()).toMillis(),
                    Instant.now(), preview, resourceType, resourceId));
        }

        private void cancelActiveTask() {
            if (activeTask != null) activeTask.cancel(true);
        }

        private String stageMessage(String currentStage) {
            return switch (currentStage) {
                case "CALLING_MODEL" -> "正在调用大模型。";
                case "VALIDATING" -> "正在校验生成结构。";
                case "PERSISTING" -> "校验通过，正在保存。";
                case "WAITING_CONFIRMATION" -> "大纲已准备好，等待你的确认。";
                default -> "正在准备生成。";
            };
        }

        private String safeContent(String value, String fallback) {
            if (value == null || value.isBlank()) return fallback;
            String safe = value.replaceAll("(?i)(api[-_ ]?key|authorization|bearer)\\s*[:=]?\\s*[^\\s,}\\\"]+", "$1=[REDACTED]");
            return safe.length() > 500 ? safe.substring(0, 500) : safe;
        }
    }
}
