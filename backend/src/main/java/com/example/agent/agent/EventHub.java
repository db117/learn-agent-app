package com.example.agent.agent;

import com.example.agent.persistence.PersistedEvent;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class EventHub {

  private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> listeners =
      new ConcurrentHashMap<>();

  public SseEmitter open(String sessionId, Supplier<List<PersistedEvent>> backlog) {
    SseEmitter emitter = new SseEmitter(0L);
    CopyOnWriteArrayList<SseEmitter> sessionListeners =
        listeners.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>());
    sessionListeners.add(emitter);
    Runnable remove = () -> remove(sessionId, emitter);
    emitter.onCompletion(remove);
    emitter.onTimeout(remove);
    try {
      emitter.send(SseEmitter.event().comment("connected"));
    } catch (IOException | IllegalStateException e) {
      remove(sessionId, emitter);
      emitter.completeWithError(e);
      return emitter;
    }
    for (PersistedEvent event : backlog.get()) {
      send(sessionId, emitter, event);
    }
    return emitter;
  }

  public void publish(PersistedEvent event) {
    for (SseEmitter emitter : listeners.getOrDefault(event.sessionId(), new CopyOnWriteArrayList<>())) {
      send(event.sessionId(), emitter, event);
    }
  }

  private void send(String sessionId, SseEmitter emitter, PersistedEvent event) {
    try {
      emitter.send(
          SseEmitter.event()
              .id(event.id())
              .data(event.json(), MediaType.TEXT_PLAIN));
    } catch (IOException | IllegalStateException e) {
      remove(sessionId, emitter);
      emitter.completeWithError(e);
    }
  }

  private void remove(String sessionId, SseEmitter emitter) {
    CopyOnWriteArrayList<SseEmitter> sessionListeners = listeners.get(sessionId);
    if (sessionListeners != null) {
      sessionListeners.remove(emitter);
      if (sessionListeners.isEmpty()) listeners.remove(sessionId, sessionListeners);
    }
  }
}
