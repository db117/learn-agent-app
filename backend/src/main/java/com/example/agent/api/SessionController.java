package com.example.agent.api;

import com.example.agent.agent.EventHub;
import com.example.agent.agent.TutorAgentService;
import com.example.agent.config.AppProperties;
import com.example.agent.persistence.SessionRecord;
import com.example.agent.persistence.SqliteRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Phase 1 Tutor 会话和 SSE 事件的 HTTP 接口。 */
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

    /** 保存用户消息并异步启动 SAA TutorAgent。 */
    @PostMapping("/{sessionId}/messages")
    public SendMessageResponse send(
            @PathVariable String sessionId, @RequestBody SendMessageRequest request) {
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
    }

    /** 以 SSE 方式订阅会话事件，已落库事件会先回放。 */
    @GetMapping(value = "/{sessionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String sessionId) {
        find(sessionId);
        return eventHub.open(sessionId, () -> repository.listEvents(sessionId));
    }

    private SessionRecord find(String id) {
        return repository
                .findSession(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found"));
    }
}
