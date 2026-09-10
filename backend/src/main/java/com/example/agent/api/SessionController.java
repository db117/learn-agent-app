package com.example.agent.api;

import com.example.agent.agent.EventHub;
import com.example.agent.agent.TutorAgentService;
import com.example.agent.config.AppProperties;
import com.example.agent.config.DatabaseTransferBusyException;
import com.example.agent.persistence.AgentStatePersistenceException;
import com.example.agent.persistence.SessionRecord;
import com.example.agent.persistence.SqliteRepository;
import com.example.agent.persistence.TutorEvent;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 第一阶段 Tutor 会话和 SSE 事件的 HTTP 接口。 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SqliteRepository repository;
    private final TutorAgentService tutor;
    private final EventHub eventHub;
    private final AppProperties properties;

    public SessionController(
            SqliteRepository repository, TutorAgentService tutor, EventHub eventHub, AppProperties properties) {
        this.repository = repository;
        this.tutor = tutor;
        this.eventHub = eventHub;
        this.properties = properties;
    }

    /** 创建一个新的 Tutor 会话。 */
    @PostMapping
    public SessionResponse create(@RequestBody(required = false) CreateSessionRequest request) {
        String title = request == null || request.title() == null || request.title().isBlank()
                ? "New session"
                : request.title().trim();
        Instant now = Instant.now();
        SessionRecord session =
                new SessionRecord(UUID.randomUUID().toString(), properties.userId(), title, now, now);
        repository.insertSession(session);
        tutor.ensureSession(session);
        return SessionResponse.from(session);
    }

    /** 查询所有已持久化的 Tutor 会话。 */
    @GetMapping
    public List<SessionResponse> list() {
        return repository.listSessions().stream().map(SessionResponse::from).toList();
    }

    /** 查询会话详情及历史消息。 */
    @GetMapping("/{sessionId}")
    public SessionDetailResponse get(@PathVariable String sessionId) {
        SessionRecord session = find(sessionId);
        return new SessionDetailResponse(
                session.id(),
                session.title(),
                session.createdAt(),
                session.updatedAt(),
                repository.listMessages(session.id()));
    }

    /** 保存用户消息并异步启动 AgentScope TutorAgent。 */
    @PostMapping("/{sessionId}/messages")
    public Mono<SendMessageResponse> send(
            @PathVariable String sessionId, @RequestBody SendMessageRequest request) {
        return Mono.fromCallable(() -> {
                    SessionRecord session = find(sessionId);
                    if (request == null || request.content() == null || request.content().isBlank()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must not be blank");
                    }
                    String content = request.content().trim();
                    if (content.length() > 20_000) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content is too long");
                    }
                    TutorAgentService.RunReceipt receipt = tutor.start(session, content);
                    return new SendMessageResponse(receipt.runId(), receipt.messageId());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 取消一个正在执行的 TutorAgent 调用。 */
    @PostMapping("/{sessionId}/runs/{runId}/cancel")
    public Mono<ResponseEntity<Map<String, String>>> cancel(
            @PathVariable String sessionId, @PathVariable String runId) {
        return Mono.fromCallable(() -> {
                    find(sessionId);
                    if (!tutor.cancel(sessionId, runId, "user_cancelled")) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found or finished");
                    }
                    return ResponseEntity.accepted().body(Map.of("status", "cancel_requested"));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 以 SSE 方式订阅会话事件，已落库事件会先回放。 */
    @GetMapping(value = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<TutorEvent>> events(
            @PathVariable String sessionId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        long lastSequence = parseLastEventId(lastEventId);
        return Mono.fromCallable(() -> find(sessionId))
                .subscribeOn(Schedulers.boundedElastic())
                .thenMany(eventHub.open(
                        sessionId,
                        lastSequence,
                        () -> repository.listEvents(sessionId),
                        () -> tutor.cancelForClientDisconnect(sessionId)))
                .map(event -> ServerSentEvent.<TutorEvent>builder(event)
                        .id(Long.toString(event.sequence()))
                        .build());
    }

    /** 不把缺失或损坏的 AgentState 静默转换成新会话。 */
    @ExceptionHandler(AgentStatePersistenceException.class)
    public ResponseEntity<Map<String, String>> agentStateError(AgentStatePersistenceException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "agent_state_restore_failed"));
    }

    /** 数据库传输持有维护窗口时，新的 Tutor 调用不能启动。 */
    @ExceptionHandler(DatabaseTransferBusyException.class)
    public ResponseEntity<Map<String, String>> databaseTransferBusy(DatabaseTransferBusyException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "database_transfer_busy", "detail", error.getMessage()));
    }

    private long parseLastEventId(String value) {
        if (value == null || value.isBlank()) return -1L;
        try {
            long sequence = Long.parseLong(value);
            if (sequence < 0) throw new NumberFormatException();
            return sequence;
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Last-Event-ID must be a non-negative integer");
        }
    }

    private SessionRecord find(String id) {
        return repository
                .findSession(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found"));
    }
}
