package com.example.agent.agent;

import com.example.agent.persistence.SqliteRepository;
import com.example.agent.persistence.TutorEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** TutorEvent 的持久化、顺序发布和历史回放边界。 */
@Component
public class TutorEventStream {

    private final SqliteRepository repository;
    private final EventHub eventHub;
    private final Object orderLock = new Object();

    public TutorEventStream(SqliteRepository repository, EventHub eventHub) {
        this.repository = repository;
        this.eventHub = eventHub;
    }

    /** 按同一顺序写入 SQLite 并发布实时事件。 */
    public TutorEvent publish(TutorEvent event) {
        synchronized (orderLock) {
            TutorEvent persisted = repository.insertEvent(event);
            eventHub.publish(persisted);
            return persisted;
        }
    }

    /** 打开带历史回放、断点续传和断开回调的会话事件流。 */
    public Flux<TutorEvent> open(String sessionId, long lastEventSequence, Runnable onClientDisconnect) {
        return eventHub.open(
                sessionId,
                lastEventSequence,
                () -> repository.listEvents(sessionId),
                onClientDisconnect);
    }

    /** 数据库替换后关闭旧数据库上的浏览器订阅。 */
    public void reset() {
        eventHub.reset();
    }
}
