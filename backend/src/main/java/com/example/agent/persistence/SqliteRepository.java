package com.example.agent.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class SqliteRepository {

  private final JdbcClient jdbc;

  public SqliteRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void insertSession(SessionRecord session) {
    jdbc.sql("""
                    INSERT INTO "session" (id, user_id, title, created_at, updated_at)
                    VALUES (:id, :userId, :title, :createdAt, :updatedAt)
                    """)
            .param("id", session.id())
            .param("userId", session.userId())
            .param("title", session.title())
            .param("createdAt", session.createdAt().toString())
            .param("updatedAt", session.updatedAt().toString())
            .update();
  }

  public List<SessionRecord> listSessions() {
    return jdbc.sql("""
                    SELECT id, user_id, title, created_at, updated_at
                    FROM "session" ORDER BY updated_at DESC
                    """)
            .query((rs, rowNum) ->
                    new SessionRecord(
                            rs.getString("id"),
                            rs.getString("user_id"),
                            rs.getString("title"),
                            Instant.parse(rs.getString("created_at")),
                            Instant.parse(rs.getString("updated_at"))))
            .list();
  }

  public Optional<SessionRecord> findSession(String id) {
    return jdbc.sql("""
                    SELECT id, user_id, title, created_at, updated_at
                    FROM "session" WHERE id = :id
                    """)
            .param("id", id)
            .query((rs, rowNum) ->
                    new SessionRecord(
                            rs.getString("id"),
                            rs.getString("user_id"),
                            rs.getString("title"),
                            Instant.parse(rs.getString("created_at")),
                            Instant.parse(rs.getString("updated_at"))))
            .optional();
  }

  public void touchSession(String sessionId, Instant updatedAt) {
    jdbc.sql("UPDATE \"session\" SET updated_at = :updatedAt WHERE id = :id")
            .param("id", sessionId)
            .param("updatedAt", updatedAt.toString())
            .update();
  }

  public void insertMessage(MessageRecord message) {
    jdbc.sql("""
                    INSERT INTO message (id, session_id, role, content, created_at)
                    VALUES (:id, :sessionId, :role, :content, :createdAt)
                    """)
            .param("id", message.id())
            .param("sessionId", message.sessionId())
            .param("role", message.role())
            .param("content", message.content())
            .param("createdAt", message.createdAt().toString())
            .update();
    touchSession(message.sessionId(), message.createdAt());
  }

  public List<MessageRecord> listMessages(String sessionId) {
    return jdbc.sql("""
                    SELECT id, session_id, role, content, created_at
                    FROM message WHERE session_id = :sessionId ORDER BY created_at, rowid
                    """)
            .param("sessionId", sessionId)
            .query((rs, rowNum) ->
                    new MessageRecord(
                            rs.getString("id"),
                            rs.getString("session_id"),
                            rs.getString("role"),
                            rs.getString("content"),
                            Instant.parse(rs.getString("created_at"))))
            .list();
  }

  public void insertRun(RunRecord run) {
    jdbc.sql("""
                    INSERT INTO agent_run (id, session_id, status, error_message, started_at, completed_at)
                    VALUES (:id, :sessionId, :status, :errorMessage, :startedAt, :completedAt)
                    """)
            .param("id", run.id())
            .param("sessionId", run.sessionId())
            .param("status", run.status())
            .param("errorMessage", run.errorMessage())
            .param("startedAt", run.startedAt().toString())
            .param("completedAt", run.completedAt() == null ? null : run.completedAt().toString())
            .update();
  }

  public void finishRun(String runId, String status, String errorMessage, Instant completedAt) {
    jdbc.sql("""
                    UPDATE agent_run SET status = :status, error_message = :errorMessage,
                      completed_at = :completedAt WHERE id = :id
                    """)
            .param("id", runId)
            .param("status", status)
            .param("errorMessage", errorMessage)
            .param("completedAt", completedAt.toString())
            .update();
  }

  public void insertEvent(PersistedEvent event) {
    jdbc.sql("""
                    INSERT OR IGNORE INTO "event"
                      (id, session_id, run_id, author, event_type, content, tool_call_json,
                       tool_result_json, timestamp, raw_json)
                    VALUES (:id, :sessionId, :runId, :author, :eventType, :content, :toolCall,
                            :toolResult, :timestamp, :rawJson)
                    """)
            .param("id", event.id())
            .param("sessionId", event.sessionId())
            .param("runId", event.runId())
            .param("author", event.author())
            .param("eventType", event.eventType())
            .param("content", event.content())
            .param("toolCall", event.toolCall())
            .param("toolResult", event.toolResult())
            .param("timestamp", event.timestamp().toString())
            .param("rawJson", event.rawJson())
            .update();
  }

  public List<PersistedEvent> listEvents(String sessionId) {
    return jdbc.sql("""
                    SELECT id, session_id, run_id, author, event_type, content, tool_call_json,
                      tool_result_json, timestamp, raw_json
                    FROM "event" WHERE session_id = :sessionId ORDER BY sequence
                    """)
            .param("sessionId", sessionId)
            .query((rs, rowNum) ->
                    new PersistedEvent(
                            rs.getString("id"),
                            rs.getString("session_id"),
                            rs.getString("run_id"),
                            rs.getString("author"),
                            rs.getString("event_type"),
                            rs.getString("content"),
                            rs.getString("tool_call_json"),
                            rs.getString("tool_result_json"),
                            Instant.parse(rs.getString("timestamp")),
                            rs.getString("raw_json")))
            .list();
  }

  public void probe() {
    String now = Instant.now().toString();
    jdbc.sql("""
                    INSERT INTO setting (key, value, updated_at) VALUES (:key, :value, :updatedAt)
                    ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                    """)
            .param("key", "health.lastChecked")
            .param("value", now)
            .param("updatedAt", now)
            .update();
    jdbc.sql("SELECT value FROM setting WHERE key = :key")
            .param("key", "health.lastChecked")
            .query(String.class)
            .single();
  }
}
