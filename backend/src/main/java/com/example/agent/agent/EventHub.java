package com.example.agent.agent;

import com.example.agent.persistence.TutorEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Component
public class EventHub {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Listener>> listeners =
            new ConcurrentHashMap<>();

    public Flux<TutorEvent> open(String sessionId, Supplier<List<TutorEvent>> backlog) {
        return open(sessionId, -1L, backlog, () -> { });
    }

    /**
     * 创建一个会话事件流，把已持久化事件和实时事件合并到同一个订阅中。
     *
     * <p>订阅建立后先注册监听器，再绑定取消和释放回调，最后读取并回放历史事件。历史回放期间到达的
     * 实时事件会先暂存在 {@code pending} 中，回放结束后再按 {@code sequence} 顺序发送；重连时通过
     * {@code lastEventSequence} 跳过客户端已经收到的事件。回放失败会移除监听器并把异常传给下游。
     * 整个订阅在 {@code boundedElastic} 上执行，避免阻塞性的历史事件读取占用 WebFlux event loop。</p>
     *
     * @param sessionId 会话标识
     * @param lastEventSequence 客户端最近收到的事件序号
     * @param backlog 提供已持久化历史事件的读取函数
     * @param onClientDisconnect 会话最后一个客户端断开时执行的回调
     * @return 包含历史事件回放和实时事件的响应式事件流
     */
    public Flux<TutorEvent> open(
            String sessionId,
            long lastEventSequence,
            Supplier<List<TutorEvent>> backlog,
            Runnable onClientDisconnect) {
        return Flux.<TutorEvent>create(sink -> {
            Listener listener = new Listener(sink, lastEventSequence);
            listeners.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>()).add(listener);
            AtomicBoolean disconnectNotified = new AtomicBoolean();
            sink.onCancel(() -> {
                if (remove(sessionId, listener) && disconnectNotified.compareAndSet(false, true)) {
                    onClientDisconnect.run();
                }
            });
            sink.onDispose(() -> remove(sessionId, listener));
            try {
                listener.replay(backlog.get());
            } catch (Throwable error) {
                remove(sessionId, listener);
                sink.error(error);
            }
        }, FluxSink.OverflowStrategy.BUFFER).subscribeOn(Schedulers.boundedElastic());
    }

    public void publish(TutorEvent event) {
        for (Listener listener : listeners.getOrDefault(event.sessionId(), new CopyOnWriteArrayList<>())) {
            listener.send(event);
        }
    }

    /** 数据库身份替换后关闭浏览器订阅，避免继续读取旧数据库事件。 */
    public void reset() {
        listeners.values().stream().flatMap(List::stream).forEach(listener -> listener.complete());
        listeners.clear();
    }

    private boolean remove(String sessionId, Listener listener) {
        CopyOnWriteArrayList<Listener> sessionListeners = listeners.get(sessionId);
        if (sessionListeners == null || !sessionListeners.remove(listener)) return false;
        boolean last = sessionListeners.isEmpty();
        if (last) listeners.remove(sessionId, sessionListeners);
        return last;
    }

    private static final class Listener {

        private final FluxSink<TutorEvent> sink;
        private final long lastEventSequence;
        private final Set<String> sent = new HashSet<>();
        private final List<TutorEvent> pending = new ArrayList<>();
        private boolean replaying = true;

        private Listener(FluxSink<TutorEvent> sink, long lastEventSequence) {
            this.sink = sink;
            this.lastEventSequence = lastEventSequence;
        }

        /**
         * 合并并回放历史事件。
         *
         * <p>该方法与 {@link #send(TutorEvent)} 共用同一把锁，保证历史回放期间到达的实时事件先进入
         * {@code pending}，不会插队或丢失；回放结束后再切换到实时发送模式。</p>
         *
         * @param events 已持久化的历史事件
         */
        private synchronized void replay(List<TutorEvent> events) {
            List<TutorEvent> ordered = new ArrayList<>();
            if (events != null) ordered.addAll(events);
            ordered.addAll(pending);
            ordered.sort(Comparator.comparingLong(TutorEvent::sequence));
            ordered.forEach(this::sendNow);
            replaying = false;
            pending.clear();
        }

        private synchronized void send(TutorEvent event) {
            if (replaying) {
                pending.add(event);
                return;
            }
            sendNow(event);
        }

        private void sendNow(TutorEvent event) {
            if (event.sequence() <= lastEventSequence) return;
            if (sent.add(event.id()) && !sink.isCancelled()) sink.next(event);
        }

        private synchronized void complete() {
            sink.complete();
        }
    }
}
