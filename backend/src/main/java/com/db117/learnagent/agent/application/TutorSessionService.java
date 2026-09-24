package com.db117.learnagent.agent.application;

import com.db117.learnagent.agent.api.CreateTutorSessionRequest;
import com.db117.learnagent.agent.api.SendTutorMessageRequest;
import com.db117.learnagent.agent.api.TutorCancelResponse;
import com.db117.learnagent.agent.api.TutorEvent;
import com.db117.learnagent.agent.api.TutorEventType;
import com.db117.learnagent.agent.api.TutorMessage;
import com.db117.learnagent.agent.api.TutorSessionMode;
import com.db117.learnagent.agent.api.TutorSessionResponse;
import com.db117.learnagent.agent.runtime.TutorAgentRuntime;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import jakarta.enterprise.context.ApplicationScoped;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode PLANNING_OUTPUT_SCHEMA = planningOutputSchema();

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
        TutorSessionService.SessionIds ids = requireSessionRequest(request);
        TutorContext context = contextAssembler.assemble(ids.learnerId(), ids.journeyId(), ids.mode());
        String sessionId = sessionId(context);
        String userId = Long.toString(context.learnerId());
        TutorSessionService.SessionBinding binding = new SessionBinding(
                sessionId,
                userId,
                context.learnerId(),
                context.journeyId(),
                context.mode(),
                context.currentLearnUnitCode());
        sessions.put(sessionId, binding);
        Optional<AgentState> state = runtime.loadState(userId, sessionId);
        return new TutorSessionResponse(
                sessionId,
                state.isPresent(),
                context.currentLearnUnitCode(),
                context.mode(),
                state.map(this::publicMessages).orElseGet(List::of));
    }

    public Flux<TutorEvent> streamTurn(String sessionId, SendTutorMessageRequest request) {
        TutorSessionService.MessageInput input = requireMessageRequest(sessionId, request);
        TutorSessionService.SessionBinding binding = sessions.get(sessionId);
        if (binding == null) {
            throw TutorRequestException.notFound("SESSION_NOT_FOUND", "Tutor Session 不存在，请先创建或恢复");
        }

        TutorContext context = contextAssembler.assemble(binding.learnerId(), binding.journeyId(), binding.mode());
        if (binding.currentLearnUnitCode() != null
                && !binding.currentLearnUnitCode().equals(context.currentLearnUnitCode())) {
            throw TutorRequestException.conflict("STALE_SESSION", "学习项已变化，请恢复新的 Tutor Session");
        }

        Semaphore lock = sessionLocks.computeIfAbsent(sessionId, ignored -> new Semaphore(1));
        if (!lock.tryAcquire()) {
            throw TutorRequestException.conflict("TURN_ACTIVE", "当前 Tutor Turn 仍在处理中");
        }

        try {
            Optional<AgentState> state = runtime.loadState(binding.userId(), sessionId);
            Optional<TutorSessionService.TurnSnapshot> previous = findTurn(state, input.turnId());
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
                throw TutorRequestException.serviceUnavailable(
                        "MODEL_UNAVAILABLE", "Tutor 模型尚未配置，请点击顶部的模型设置按钮完成配置");
            }

            UserMessage userMessage = UserMessage.builder()
                    .name("learner")
                    .textContent(input.text())
                    .metadata(Map.of(TURN_ID, input.turnId(), TURN_STATUS, RUNNING))
                    .build();
            TutorSessionService.ActiveTurn active = new ActiveTurn(
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
        TutorSessionService.ActiveTurn active = activeTurns.get(sessionId);
        if (active == null) {
            return new TutorCancelResponse(false);
        }
        active.cancelRequested.set(true);
        runtime.interrupt(active.context);
        return new TutorCancelResponse(true);
    }

    private Flux<TutorEvent> run(ActiveTurn active, Semaphore lock) {
        Flux<TutorEvent> prefix = Flux.just(
                active.event(TutorEventType.TURN_STARTED, null, null),
                active.event(TutorEventType.ACTIVITY, "正在加载学习上下文", null),
                active.event(TutorEventType.ACTIVITY, "模型正在组织回答", null));
        try {
            // streamEvents 没有 JsonNode Schema 重载；规划模式继续用结构化 call 保持输出约束。
            Flux<AgentEvent> modelEvents = active.binding.mode() == TutorSessionMode.PLANNING
                    ? runtime.agent()
                    .call(List.of(active.userMessage), PLANNING_OUTPUT_SCHEMA, active.context)
                    .map(result -> (AgentEvent) new AgentResultEvent(result))
                    .flux()
                    : runtime.agent().streamEvents(List.of(active.userMessage), active.context);
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

    private static JsonNode planningOutputSchema() {
        try {
            return JSON.readTree("""
                    {
                      "type": "object",
                      "additionalProperties": false,
                      "properties": {
                        "chapters": {
                          "type": "array",
                          "minItems": 1,
                          "maxItems": 50,
                          "items": {
                            "type": "object",
                            "additionalProperties": false,
                            "properties": {
                              "code": {"type": "string", "pattern": "[a-z][a-z0-9]*(?:-[a-z0-9]+)+"},
                              "title": {"type": "string"},
                              "units": {
                                "type": "array",
                                "minItems": 1,
                                "maxItems": 50,
                                "items": {
                                  "type": "object",
                                  "additionalProperties": false,
                                  "properties": {
                                    "code": {"type": "string", "pattern": "[a-z][a-z0-9]*(?:-[a-z0-9]+)+"},
                                    "title": {"type": "string"},
                                    "objective": {"type": "string"}
                                  },
                                  "required": ["code", "title", "objective"]
                                }
                              }
                            },
                            "required": ["code", "title", "units"]
                          }
                        }
                      },
                      "required": ["chapters"]
                    }
                    """);
        } catch (JsonProcessingException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private Flux<TutorEvent> project(Flux<AgentEvent> events, ActiveTurn active) {
        return events.handle((event, sink) -> {
            for (TutorEventMapper.Projection projection : TutorEventMapper.map(event)) {
                sink.next(active.event(projection.type(), projection.text(), projection.errorCode()));
            }
            if (event instanceof TextBlockDeltaEvent textDelta) {
                emitVisibleText(textDelta.getDelta(), active, sink);
            } else if (event instanceof AgentResultEvent result && !active.hasDelta.get()) {
                emitVisibleText(result.getResult(), active, sink);
            }
        });
    }

    private void emitVisibleText(
            String text,
            ActiveTurn active,
            reactor.core.publisher.SynchronousSink<TutorEvent> sink) {
        if (text == null || text.isBlank()) {
            return;
        }
        active.hasDelta.set(true);
        active.answer.append(text);
        sink.next(active.event(TutorEventType.MESSAGE_DELTA, text, null));
    }

    private void emitVisibleText(
            Msg message,
            ActiveTurn active,
            reactor.core.publisher.SynchronousSink<TutorEvent> sink) {
        if (message == null) {
            return;
        }
        for (TextBlock block : message.getContentBlocks(TextBlock.class)) {
            emitVisibleText(block.getText(), active, sink);
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
        if (active.answer.toString().isBlank()) {
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
        AgentState state = runtime.loadState(active.binding.userId(), active.binding.sessionId())
                .orElseGet(() -> AgentState.builder()
                        .sessionId(active.binding.sessionId())
                        .userId(active.binding.userId())
                        .context(new ArrayList<>())
                        .build());
        List<Msg> messages = state.contextMutable();
        int userIndex = findTurnIndex(messages, active.turnId);
        if (userIndex < 0) {
            messages.add(active.userMessage);
            userIndex = messages.size() - 1;
        }
        Msg user = messages.get(userIndex);
        HashMap<String, Object> metadata = new HashMap<>(user.getMetadata());
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
        ArrayList<TutorMessage> messages = new ArrayList<TutorMessage>();
        for (Msg message : state.getContext()) {
            if (message.getRole() != MsgRole.USER && message.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            String text = message.getTextContent();
            if (text == null || text.isEmpty()) {
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
        TutorSessionService.ReplayTurn active = new ReplayTurn(binding, turnId);
        ArrayList<TutorEvent> events = new ArrayList<TutorEvent>();
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
        List<Msg> messages = state.get().getContext();
        for (int index = messages.size() - 1; index >= 0; index--) {
            Msg message = messages.get(index);
            if (!turnId.equals(metadataText(message, TURN_ID))) {
                continue;
            }
            String status = metadataText(message, TURN_STATUS);
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
            Msg message = messages.get(index);
            if (message.getRole() == MsgRole.USER) {
                return Optional.empty();
            }
            if (message.getRole() == MsgRole.ASSISTANT) {
                String text = message.getTextContent();
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
        Object value = message.getMetadata().get(key);
        return value == null ? null : value.toString();
    }

    private boolean containsInterrupted(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
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
        return new SessionIds(
                request.learnerId(),
                request.journeyId(),
                request.mode() == null ? TutorSessionMode.LEARNING : request.mode());
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
        String seed = context.learnerId() + "|" + context.journeyId() + "|"
                + context.mode() + "|" + Objects.toString(context.currentLearnUnitCode(), "planning");
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * 创建 Session 请求中的 Domain 标识和运行模式。
     *
     * @param learnerId 请求中的 Learner Domain ID
     * @param journeyId 请求中的 Journey Domain ID
     * @param mode 规划或学习模式
     */
    private record SessionIds(
            long learnerId,
            long journeyId,
            TutorSessionMode mode) {
    }

    /**
     * 已完成边界校验的 Tutor 消息输入。
     *
     * @param turnId 已规范化的客户端 Turn ID
     * @param text 经边界校验的学习者输入
     */
    private record MessageInput(
            String turnId,
            String text) {
    }

    /**
     * Tutor Session 与 Domain 上下文的稳定绑定。
     *
     * @param sessionId 稳定的 Session ID
     * @param userId AgentScope 状态存储使用的用户分区
     * @param learnerId 绑定的 Learner Domain ID
     * @param journeyId 绑定的 Journey Domain ID
     * @param mode 绑定的 Tutor Session 模式
     * @param currentLearnUnitCode 创建绑定时的当前 LearnUnit 编码
     */
    private record SessionBinding(
            String sessionId,
            String userId,
            long learnerId,
            long journeyId,
            TutorSessionMode mode,
            String currentLearnUnitCode) {
    }

    /**
     * Agent State 中已记录的 Turn 快照。
     *
     * @param status Agent State 中记录的 Turn 终态
     * @param answer 已完成 Turn 的公开回答；失败或取消时为空
     */
    private record TurnSnapshot(
            String status,
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
            long next = sequence.incrementAndGet();
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
