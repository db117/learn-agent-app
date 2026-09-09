package com.example.agent.agent;

import com.example.agent.persistence.TutorEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

@Component
public class EventHub {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Listener>> listeners =
            new ConcurrentHashMap<>();

    public Flux<TutorEvent> open(String sessionId, Supplier<List<TutorEvent>> backlog) {
        return Flux.<TutorEvent>create(sink -> {
            Listener listener = new Listener(sink);
            listeners.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>()).add(listener);
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

    private void remove(String sessionId, Listener listener) {
        CopyOnWriteArrayList<Listener> sessionListeners = listeners.get(sessionId);
        if (sessionListeners != null) {
            sessionListeners.remove(listener);
            if (sessionListeners.isEmpty()) listeners.remove(sessionId, sessionListeners);
        }
    }

    private static final class Listener {

        private final FluxSink<TutorEvent> sink;
        private final Set<String> sent = new HashSet<>();

        private Listener(FluxSink<TutorEvent> sink) {
            this.sink = sink;
        }

        private synchronized void replay(List<TutorEvent> events) {
            events.forEach(this::send);
        }

        private synchronized void send(TutorEvent event) {
            if (sent.add(event.id()) && !sink.isCancelled()) sink.next(event);
        }
    }
}
