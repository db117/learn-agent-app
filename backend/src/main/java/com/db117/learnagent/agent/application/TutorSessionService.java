package com.db117.learnagent.agent.application;

import com.db117.learnagent.agent.api.*;
import com.db117.learnagent.agent.runtime.TutorAgentRuntime;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.*;
import io.agentscope.core.state.AgentState;
import jakarta.enterprise.context.ApplicationScoped;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Tutor Session 的生命周期边界；Learning Domain 仍是进度事实的唯一来源。 */
@ApplicationScoped
public class TutorSessionService {
    static final int MAX_MESSAGE_LENGTH = 8_000;
    private static final int MAX_TURN_ID_LENGTH = 128;
    private static final String TURN_ID = "learn-agent.turn-id";
    private static final String TURN_STATUS = "learn-agent.turn-status";
    private static final String RUNNING = "RUNNING";
    private static final String COMPLETED = "COMPLETED";
    private static final String FAILED = "FAILED";
    private static final String CANCELLED = "CANCELLED";
    private static final StreamOptions STREAM_OPTIONS = StreamOptions.builder()
            .eventTypes(EventType.ALL)
            .incremental(true)
            .includeReasoningChunk(true)
            .includeReasoningResult(true)
            .build();

    private final TutorContextAssembler contextAssembler;
    private final TutorAgentRuntime runtime;
    private final ConcurrentHashMap<String, SessionBinding> sessions = new ConcurrentHashMap<>();
    // ponytail: 每个稳定 Session 保留一个轻量锁；若 Session 数量成为问题，再增加闲置锁回收。
    private final ConcurrentHashMap<String, Semaphore> sessionLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActiveTurn> activeTurns = new ConcurrentHashMap<>();

    public TutorSessionService(TutorContextAssembler contextAssembler, TutorAgentRuntime runtime) {
        this.contextAssembler = contextAssembler;
        this.runtime = runtime;
    }

    public TutorSessionResponse createSession(CreateTutorSessionRequest request) {
        var ids = requireSessionRequest(request);
        var context = contextAssembler.assemble(ids.learnerId(), ids.journeyId());
        var sessionId = sessionId(context);
        var userId = Long.toString(context.learnerId());
        var binding = new SessionBinding(
                sessionId,
                userId,
                context.learnerId(),
                context.journeyId(),
                context.currentLearnUnitCode());
        sessions.put(sessionId, binding);
        var state = runtime.loadState(userId, sessionId);
        return new TutorSessionResponse(
                sessionId,
                state.isPresent(),
                context.currentLearnUnitCode(),
                state.map(this::publicMessages).orElseGet(List::of));
    }

    public Flux<TutorEvent> streamTurn(String sessionId, SendTutorMessageRequest request) {
        var input = requireMessageRequest(sessionId, request);
        var binding = sessions.get(sessionId);
        if (binding == null) {
            throw TutorRequestException.notFound("SESSION_NOT_FOUND", "Tutor Session 不存在，请先创建或恢复");
        }

        var context = contextAssembler.assemble(binding.learnerId(), binding.journeyId());
        if (!context.currentLearnUnitCode().equals(binding.currentLearnUnitCode())) {
            throw TutorRequestException.conflict("STALE_SESSION", "学习项已变化，请恢复新的 Tutor Session");
        }

        var lock = sessionLocks.computeIfAbsent(sessionId, ignored -> new Semaphore(1));
        if (!lock.tryAcquire()) {
            throw TutorRequestException.conflict("TURN_ACTIVE", "当前 Tutor Turn 仍在处理中");
        }

        try {
            var state = runtime.loadState(binding.userId(), sessionId);
            var previous = findTurn(state, input.turnId());
            if (previous.isPresent()) {
                if (RUNNING.equals(previous.get().status())) {
                    throw TutorRequestException.conflict("TURN_ACTIVE", "当前 Tutor Turn 仍在处理中");
                }
                return replayTerminal(binding, input.turnId(), previous.get())
                        .doFinally(ignored -> lock.release());
            }
            if (state.map(this::hasRunningTurn).orElse(false)) {
                throw TutorRequestException.conflict("TURN_ACTIVE", "当前 Tutor Turn 仍在处理中");
            }
            if (!runtime.configured()) {
                throw TutorRequestException.serviceUnavailable("MODEL_UNAVAILABLE", "Tutor 模型尚未配置");
            }

            var userMessage = UserMessage.builder()
                    .name("learner")
                    .textContent(input.text())
                    .metadata(Map.of(TURN_ID, input.turnId(), TURN_STATUS, RUNNING))
                    .build();
            var active = new ActiveTurn(
                    binding,
                    input.turnId(),
                    userMessage,
                    runtime.context(sessionId, binding.userId(), context));
            if (activeTurns.putIfAbsent(sessionId, active) != null) {
                throw TutorRequestException.conflict("TURN_ACTIVE", "当前 Tutor Turn 仍在处理中");
            }
            return run(active, lock);
        } catch (RuntimeException error) {
            lock.release();
            throw error;
        }
    }

    public TutorCancelResponse cancel(String sessionId) {
        if (!sessions.containsKey(sessionId)) {
            throw TutorRequestException.notFound("SESSION_NOT_FOUND", "Tutor Session 不存在，请先创建或恢复");
        }
        var active = activeTurns.get(sessionId);
        if (active == null) {
            return new TutorCancelResponse(false);
        }
        active.cancelRequested.set(true);
        runtime.interrupt(active.context);
        return new TutorCancelResponse(true);
    }

    @SuppressWarnings("deprecation")
    private Flux<TutorEvent> run(ActiveTurn active, Semaphore lock) {
        var prefix = Flux.just(
                active.event(TutorEventType.TURN_STARTED, null, null),
                active.event(TutorEventType.ACTIVITY, "正在加载学习上下文", null),
                active.event(TutorEventType.ACTIVITY, "模型正在组织回答", null));
        try {
            var modelEvents = runtime.agent().stream(
                    List.of(active.userMessage), STREAM_OPTIONS, active.context);
            return Flux.concat(
                            prefix,
                            project(modelEvents, active),
                            Flux.defer(() -> complete(active)))
                    .onErrorResume(error -> fail(active, error))
                    .doFinally(signal -> cleanup(active, lock, signal));
        } catch (RuntimeException error) {
            try {
                if (active.terminal.compareAndSet(false, true)) {
                    persistTerminal(active, FAILED);
                }
            } catch (RuntimeException ignored) {
                // Provider 建立失败和状态写入失败都只返回稳定的公开错误，不泄露内部文本。
            } finally {
                activeTurns.remove(active.binding.sessionId(), active);
            }
            throw TutorRequestException.serviceUnavailable("MODEL_REQUEST_FAILED", "Tutor 模型暂时不可用");
        }
    }

    private Flux<TutorEvent> project(Flux<Event> events, ActiveTurn active) {
        return events.handle((event, sink) -> {
            if (event.getType() == EventType.REASONING && !event.isLast()) {
                emitVisibleText(event.getMessage(), active, sink);
            } else if (event.getType() == EventType.AGENT_RESULT && !active.hasDelta.get()) {
                emitVisibleText(event.getMessage(), active, sink);
            }
        });
    }

    private void emitVisibleText(
            Msg message,
            ActiveTurn active,
            reactor.core.publisher.SynchronousSink<TutorEvent> sink) {
        if (message == null) {
            return;
        }
        for (var block : message.getContentBlocks(TextBlock.class)) {
            var text = block.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            active.hasDelta.set(true);
            active.answer.append(text);
            sink.next(active.event(TutorEventType.MESSAGE_DELTA, text, null));
        }
    }

    private Flux<TutorEvent> complete(ActiveTurn active) {
        if (!active.terminal.compareAndSet(false, true)) {
            return Flux.empty();
        }
        if (active.cancelRequested.get()) {
            persistTerminal(active, CANCELLED);
            return Flux.just(active.event(TutorEventType.TURN_CANCELLED, null, "TURN_CANCELLED"));
        }
        if (active.answer.isEmpty()) {
            persistTerminal(active, FAILED);
            return Flux.just(active.event(TutorEventType.TURN_FAILED, null, "EMPTY_RESPONSE"));
        }
        persistTerminal(active, COMPLETED);
        return Flux.just(active.event(TutorEventType.TURN_COMPLETED, null, null));
    }

    private Flux<TutorEvent> fail(ActiveTurn active, Throwable error) {
        if (!active.terminal.compareAndSet(false, true)) {
            return Flux.empty();
        }
        if (active.cancelRequested.get() || containsInterrupted(error)) {
            persistTerminal(active, CANCELLED);
            return Flux.just(active.event(TutorEventType.TURN_CANCELLED, null, "TURN_CANCELLED"));
        }
        persistTerminal(active, FAILED);
        return Flux.just(active.event(TutorEventType.TURN_FAILED, null, "MODEL_REQUEST_FAILED"));
    }

    private void cleanup(ActiveTurn active, Semaphore lock, reactor.core.publisher.SignalType signal) {
        try {
            if (signal == reactor.core.publisher.SignalType.CANCEL && active.terminal.compareAndSet(false, true)) {
                active.cancelRequested.set(true);
                runtime.interrupt(active.context);
                persistTerminal(active, CANCELLED);
            }
        } finally {
            activeTurns.remove(active.binding.sessionId(), active);
            lock.release();
        }
    }

    private void persistTerminal(ActiveTurn active, String status) {
        var state = runtime.loadState(active.binding.userId(), active.binding.sessionId())
                .orElseGet(() -> AgentState.builder()
                        .sessionId(active.binding.sessionId())
                        .userId(active.binding.userId())
                        .context(new ArrayList<>())
                        .build());
        var messages = state.contextMutable();
        var userIndex = findTurnIndex(messages, active.turnId);
        if (userIndex < 0) {
            messages.add(active.userMessage);
            userIndex = messages.size() - 1;
        }
        var user = messages.get(userIndex);
        var metadata = new HashMap<>(user.getMetadata());
        metadata.put(TURN_STATUS, status);
        messages.set(userIndex, user.withMetadata(metadata));

        if (!COMPLETED.equals(status)) {
            removeAssistantMessagesAfter(messages, userIndex);
        } else if (findAssistantAfter(messages, userIndex).isEmpty()) {
            messages.add(AssistantMessage.builder().textContent(active.answer.toString()).build());
        }
        runtime.stateStore().save(
                active.binding.userId(),
                active.binding.sessionId(),
                "agent_state",
                state);
        runtime.clearStateCache(active.binding.userId(), active.binding.sessionId());
    }

    private List<TutorMessage> publicMessages(AgentState state) {
        var messages = new ArrayList<TutorMessage>();
        for (var message : state.getContext()) {
            if (message.getRole() != MsgRole.USER && message.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            var text = message.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            messages.add(new TutorMessage(
                    message.getRole() == MsgRole.USER ? "user" : "assistant",
                    text,
                    message.getTimestamp()));
        }
        return List.copyOf(messages);
    }

    private Flux<TutorEvent> replayTerminal(
            SessionBinding binding,
            String turnId,
            TurnSnapshot previous) {
        var active = new ReplayTurn(binding, turnId);
        var events = new ArrayList<TutorEvent>();
        events.add(active.event(TutorEventType.TURN_STARTED, null, null));
        events.add(active.event(TutorEventType.ACTIVITY, "正在恢复已完成的 Tutor Turn", null));
        if (COMPLETED.equals(previous.status()) && previous.answer() != null && !previous.answer().isBlank()) {
            events.add(active.event(TutorEventType.MESSAGE_DELTA, previous.answer(), null));
            events.add(active.event(TutorEventType.TURN_COMPLETED, null, null));
        } else if (CANCELLED.equals(previous.status())) {
            events.add(active.event(TutorEventType.TURN_CANCELLED, null, "TURN_CANCELLED"));
        } else {
            events.add(active.event(TutorEventType.TURN_FAILED, null, "MODEL_REQUEST_FAILED"));
        }
        return Flux.fromIterable(events);
    }

    private Optional<TurnSnapshot> findTurn(Optional<AgentState> state, String turnId) {
        if (state.isEmpty()) {
            return Optional.empty();
        }
        var messages = state.get().getContext();
        for (int index = messages.size() - 1; index >= 0; index--) {
            var message = messages.get(index);
            if (!turnId.equals(metadataText(message, TURN_ID))) {
                continue;
            }
            var status = metadataText(message, TURN_STATUS);
            if (status == null) {
                return Optional.of(new TurnSnapshot(RUNNING, null));
            }
            return Optional.of(new TurnSnapshot(status, findAssistantAfter(messages, index).orElse(null)));
        }
        return Optional.empty();
    }

    private boolean hasRunningTurn(AgentState state) {
        return state.getContext().stream()
                .anyMatch(message -> RUNNING.equals(metadataText(message, TURN_STATUS)));
    }

    private Optional<String> findAssistantAfter(List<Msg> messages, int userIndex) {
        for (int index = userIndex + 1; index < messages.size(); index++) {
            var message = messages.get(index);
            if (message.getRole() == MsgRole.USER) {
                return Optional.empty();
            }
            if (message.getRole() == MsgRole.ASSISTANT) {
                var text = message.getTextContent();
                return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
            }
        }
        return Optional.empty();
    }

    private void removeAssistantMessagesAfter(List<Msg> messages, int userIndex) {
        for (int index = messages.size() - 1; index > userIndex; index--) {
            if (messages.get(index).getRole() == MsgRole.USER) {
                break;
            }
            if (messages.get(index).getRole() == MsgRole.ASSISTANT) {
                messages.remove(index);
            }
        }
    }

    private int findTurnIndex(List<Msg> messages, String turnId) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (turnId.equals(metadataText(messages.get(index), TURN_ID))) {
                return index;
            }
        }
        return -1;
    }

    private String metadataText(Msg message, String key) {
        var value = message.getMetadata().get(key);
        return value == null ? null : value.toString();
    }

    private boolean containsInterrupted(Throwable error) {
        for (var current = error; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    private SessionIds requireSessionRequest(CreateTutorSessionRequest request) {
        if (request == null || request.learnerId() == null || request.journeyId() == null
                || request.learnerId() <= 0 || request.journeyId() <= 0) {
            throw TutorRequestException.badRequest("INVALID_SESSION_REQUEST", "learnerId 和 journeyId 必须为正数");
        }
        return new SessionIds(request.learnerId(), request.journeyId());
    }

    private MessageInput requireMessageRequest(String sessionId, SendTutorMessageRequest request) {
        if (sessionId == null || sessionId.isBlank()) {
            throw TutorRequestException.badRequest("INVALID_SESSION_ID", "sessionId 不能为空");
        }
        if (request == null || request.turnId() == null || request.turnId().isBlank()
                || request.turnId().length() > MAX_TURN_ID_LENGTH) {
            throw TutorRequestException.badRequest("INVALID_TURN_ID", "turnId 无效");
        }
        if (request.text() == null || request.text().isBlank()) {
            throw TutorRequestException.badRequest("EMPTY_MESSAGE", "消息不能为空");
        }
        if (request.text().length() > MAX_MESSAGE_LENGTH) {
            throw TutorRequestException.badRequest("MESSAGE_TOO_LONG", "消息长度超过限制");
        }
        return new MessageInput(request.turnId().strip(), request.text());
    }

    private String sessionId(TutorContext context) {
        var seed = context.learnerId() + "|" + context.journeyId() + "|" + context.currentLearnUnitCode();
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record SessionIds(
            /** 请求中的 Learner Domain ID。 */
            long learnerId,
            /** 请求中的 LearningJourney Domain ID。 */
            long journeyId) {
    }

    private record MessageInput(
            /** 已规范化的客户端 Turn ID。 */
            String turnId,
            /** 经边界校验的学习者输入。 */
            String text) {
    }

    private record SessionBinding(
            /** 稳定的 Session ID。 */
            String sessionId,
            /** AgentScope 状态存储使用的用户分区。 */
            String userId,
            /** 绑定的 Learner Domain ID。 */
            long learnerId,
            /** 绑定的 LearningJourney Domain ID。 */
            long journeyId,
            /** 创建绑定时的当前 LearnUnit 编码。 */
            String currentLearnUnitCode) {
    }

    private record TurnSnapshot(
            /** Agent State 中记录的 Turn 终态。 */
            String status,
            /** 已完成 Turn 的公开回答；失败或取消时为空。 */
            String answer) {
    }

    private static final class ActiveTurn {
        private final SessionBinding binding;
        private final String turnId;
        private final UserMessage userMessage;
        private final RuntimeContext context;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicBoolean hasDelta = new AtomicBoolean();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final StringBuilder answer = new StringBuilder();

        private ActiveTurn(
                SessionBinding binding,
                String turnId,
                UserMessage userMessage,
                RuntimeContext context) {
            this.binding = binding;
            this.turnId = turnId;
            this.userMessage = userMessage;
            this.context = context;
        }

        private TutorEvent event(TutorEventType type, String text, String errorCode) {
            var next = sequence.incrementAndGet();
            return new TutorEvent(next, type, binding.sessionId(), turnId, next, text, errorCode);
        }
    }

    private static final class ReplayTurn {
        private final SessionBinding binding;
        private final String turnId;
        private long sequence;

        private ReplayTurn(SessionBinding binding, String turnId) {
            this.binding = binding;
            this.turnId = turnId;
        }

        private TutorEvent event(TutorEventType type, String text, String errorCode) {
            sequence++;
            return new TutorEvent(sequence, type, binding.sessionId(), turnId, sequence, text, errorCode);
        }
    }
}
