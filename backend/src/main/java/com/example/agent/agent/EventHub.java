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
    }
}
