package com.example.agent.agent;

import com.example.agent.config.AppProperties;
import com.example.agent.persistence.MessageRecord;
import com.example.agent.persistence.PersistedEvent;
import com.example.agent.persistence.RunRecord;
import com.example.agent.persistence.SessionRecord;
import com.example.agent.persistence.SqliteRepository;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.adk.agents.RunConfig;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class TutorAgentService {

  private final Runner runner;
  private final InMemorySessionService adkSessions;
  private final SqliteRepository repository;
  private final EventHub eventHub;
  private final AppProperties properties;
  private final ExecutorService executor;
  private final ConcurrentHashMap<String, Boolean> activeSessions = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, StringBuilder> responseText = new ConcurrentHashMap<>();

  public TutorAgentService(
      Runner runner,
      InMemorySessionService adkSessions,
      SqliteRepository repository,
      EventHub eventHub,
      AppProperties properties,
      ExecutorService executor) {
    this.runner = runner;
    this.adkSessions = adkSessions;
    this.repository = repository;
    this.eventHub = eventHub;
    this.properties = properties;
    this.executor = executor;
  }

  public synchronized void ensureAdkSession(SessionRecord session) {
    Session adkSession =
        adkSessions
            .getSession(properties.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();
    if (adkSession == null) {
      adkSession =
          adkSessions
              .createSession(properties.appName(), session.userId(), Map.of(), session.id())
              .blockingGet();
      for (MessageRecord message : repository.listMessages(session.id())) {
        String author = "user".equals(message.role()) ? "user" : "tutor_agent";
        adkSessions
            .appendEvent(
                adkSession,
                Event.builder()
                    .id(message.id())
                    .invocationId("restored-" + message.id())
                    .author(author)
                    .content(
                        Content.builder()
                            .role("user".equals(author) ? "user" : "model")
                            .parts(Part.fromText(message.content()))
                            .build())
                    .timestamp(message.createdAt().toEpochMilli())
                    .build())
            .blockingGet();
      }
    }
  }

  public RunReceipt start(SessionRecord session, String content) {
    ensureAdkSession(session);
    String messageId = UUID.randomUUID().toString();
    String runId = UUID.randomUUID().toString();
    Instant now = Instant.now();
    repository.insertMessage(new MessageRecord(messageId, session.id(), "user", content, now));
    repository.insertRun(new RunRecord(runId, session.id(), "RUNNING", null, now, null));
    activeSessions.put(session.id(), Boolean.TRUE);
    executor.submit(() -> execute(session, runId, content));
    return new RunReceipt(runId, messageId);
  }

  public boolean active(String sessionId) {
    return activeSessions.containsKey(sessionId);
  }

  private void execute(SessionRecord session, String runId, String content) {
    try {
      Flowable<Event> events =
          runner.runAsync(
              session.userId(),
              session.id(),
              Content.fromParts(Part.fromText(content)),
              RunConfig.builder()
                  .streamingMode(RunConfig.StreamingMode.SSE)
                  .toolExecutionMode(RunConfig.ToolExecutionMode.SEQUENTIAL)
                  .build());
      events.blockingSubscribe(
          event -> persistEvent(session, runId, event),
          error -> finishFailed(session.id(), runId, error),
          () -> finishCompleted(session.id(), runId));
    } catch (Throwable error) {
      finishFailed(session.id(), runId, error);
    }
  }

  private void persistEvent(SessionRecord session, String runId, Event event) {
    PersistedEvent persisted = PersistedEvent.from(session.id(), runId, event);
    repository.insertEvent(persisted);
    eventHub.publish(persisted);
    if (event.author() != null
        && !"user".equals(event.author())
        && event.functionCalls().isEmpty()
        && event.functionResponses().isEmpty()) {
      String text = textOf(event);
      if (!text.isBlank()) {
        responseText.computeIfAbsent(runId, ignored -> new StringBuilder()).append(text);
      }
      if (event.finalResponse()) {
        StringBuilder accumulated = responseText.remove(runId);
        String finalText = accumulated == null ? "" : accumulated.toString();
        if (!finalText.isBlank()) {
          repository.insertMessage(
              new MessageRecord(event.id(), session.id(), "assistant", finalText, persisted.timestamp()));
        }
      }
    }
  }

  private String textOf(Event event) {
    return event.content()
        .flatMap(Content::parts)
        .orElseGet(java.util.List::of)
        .stream()
        .flatMap(part -> part.text().stream())
        .collect(Collectors.joining());
  }

  private void finishCompleted(String sessionId, String runId) {
    repository.finishRun(runId, "COMPLETED", null, Instant.now());
    responseText.remove(runId);
    activeSessions.remove(sessionId);
  }

  private void finishFailed(String sessionId, String runId, Throwable error) {
    PersistedEvent persisted = PersistedEvent.error(sessionId, runId, error);
    repository.insertEvent(persisted);
    eventHub.publish(persisted);
    repository.finishRun(runId, "FAILED", persisted.content(), Instant.now());
    responseText.remove(runId);
    activeSessions.remove(sessionId);
  }

  public record RunReceipt(String runId, String messageId) {}
}
