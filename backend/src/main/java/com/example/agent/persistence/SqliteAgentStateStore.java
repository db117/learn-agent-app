package com.example.agent.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JsonUtils;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

/**
 * 基于 SQLite 的 AgentScope 状态存储。
 *
 * <p>JDBC 调用是阻塞操作，必须在 event loop 之外执行。</p>
 */
public final class SqliteAgentStateStore implements AgentStateStore {

    private static final String ANONYMOUS_USER = "__anon__";
    private static final String SINGLE = "single";
    private static final String LIST = "list";
    private static final ObjectMapper JSON_TREE = new ObjectMapper();

    private final JdbcClient jdbc;

    public SqliteAgentStateStore(DataSource dataSource) {
        this(JdbcClient.create(dataSource));
    }

    SqliteAgentStateStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        upsert(userId, sessionId, key, SINGLE, json(value));
    }

    @Override
    public boolean supportsVersioning() {
        return true;
    }

    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        StateRow row = find(userId, sessionId, key).orElse(null);
        if (row == null) return new VersionedState<>(null, 0L);
        if (!SINGLE.equals(row.kind())) {
            throw restoreFailure(sessionId, null);
        }
        return new VersionedState<>(decode(row.json(), type, sessionId), row.version());
    }

    /**
     * 按期望版本执行 AgentState 的乐观并发写入。
     *
     * <p>未版本化状态使用 upsert，期望版本为 0 时只尝试插入，正版本使用条件更新；任一步发生版本冲突
     * 都返回 {@link AgentStateStore#UNVERSIONED}，由 AgentScope 决定后续处理。</p>
     */
    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        String normalizedUser = normalizeUser(userId);
        String normalizedSession = requireSessionId(sessionId);
        String normalizedKey = requireKey(key);
        String stateJson = json(value);
        String updatedAt = Instant.now().toString();

        if (expectedVersion == UNVERSIONED) {
            jdbc.sql("""
                    INSERT INTO agent_state
                      (user_id, session_id, state_key, state_kind, state_json, version, updated_at)
                    VALUES (:userId, :sessionId, :stateKey, :stateKind, :stateJson, 1, :updatedAt)
                    ON CONFLICT(user_id, session_id, state_key) DO UPDATE SET
                      state_kind = excluded.state_kind,
                      state_json = excluded.state_json,
                      version = agent_state.version + 1,
                      updated_at = excluded.updated_at
                    """)
                    .param("userId", normalizedUser)
                    .param("sessionId", normalizedSession)
                    .param("stateKey", normalizedKey)
                    .param("stateKind", SINGLE)
                    .param("stateJson", stateJson)
                    .param("updatedAt", updatedAt)
                    .update();
            return findVersion(normalizedUser, normalizedSession, normalizedKey);
        }

        if (expectedVersion == 0L) {
            int inserted = jdbc.sql("""
                    INSERT INTO agent_state
                      (user_id, session_id, state_key, state_kind, state_json, version, updated_at)
                    VALUES (:userId, :sessionId, :stateKey, :stateKind, :stateJson, 1, :updatedAt)
                    ON CONFLICT(user_id, session_id, state_key) DO NOTHING
                    """)
                    .param("userId", normalizedUser)
                    .param("sessionId", normalizedSession)
                    .param("stateKey", normalizedKey)
                    .param("stateKind", SINGLE)
                    .param("stateJson", stateJson)
                    .param("updatedAt", updatedAt)
                    .update();
            return inserted == 1 ? 1L : UNVERSIONED;
        }

        if (expectedVersion < 0L) return UNVERSIONED;
        int updated = jdbc.sql("""
                UPDATE agent_state
                SET state_kind = :stateKind, state_json = :stateJson,
                    version = version + 1, updated_at = :updatedAt
                WHERE user_id = :userId AND session_id = :sessionId AND state_key = :stateKey
                  AND version = :expectedVersion
                """)
                .param("userId", normalizedUser)
                .param("sessionId", normalizedSession)
                .param("stateKey", normalizedKey)
                .param("stateKind", SINGLE)
                .param("stateJson", stateJson)
                .param("updatedAt", updatedAt)
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1 ? expectedVersion + 1 : UNVERSIONED;
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        Objects.requireNonNull(values, "values must not be null");
        upsert(userId, sessionId, key, LIST, json(values));
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        StateRow row = find(userId, sessionId, key).orElse(null);
        if (row == null) return Optional.empty();
        if (!SINGLE.equals(row.kind())) throw restoreFailure(sessionId, null);
        return Optional.of(decode(row.json(), type, sessionId));
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        StateRow row = find(userId, sessionId, key).orElse(null);
        if (row == null) return List.of();
        if (!LIST.equals(row.kind())) throw restoreFailure(sessionId, null);
        try {
            JsonNode values = JSON_TREE.readTree(row.json());
            if (values == null || !values.isArray()) throw new IllegalArgumentException("not an array");
            return java.util.stream.StreamSupport.stream(values.spliterator(), false)
                    .map(value -> JsonUtils.getJsonCodec().fromJson(value.toString(), itemType))
                    .toList();
        } catch (Exception error) {
            throw restoreFailure(sessionId, error);
        }
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        String normalizedUser = normalizeUser(userId);
        String normalizedSession = requireSessionId(sessionId);
        Integer count = jdbc.sql("""
                SELECT COUNT(*) FROM agent_state
                WHERE user_id = :userId AND session_id = :sessionId
                """)
                .param("userId", normalizedUser)
                .param("sessionId", normalizedSession)
                .query(Integer.class)
                .single();
        return count > 0;
    }

    @Override
    public void delete(String userId, String sessionId) {
        jdbc.sql("DELETE FROM agent_state WHERE user_id = :userId AND session_id = :sessionId")
                .param("userId", normalizeUser(userId))
                .param("sessionId", requireSessionId(sessionId))
                .update();
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        jdbc.sql("""
                DELETE FROM agent_state
                WHERE user_id = :userId AND session_id = :sessionId AND state_key = :stateKey
                """)
                .param("userId", normalizeUser(userId))
                .param("sessionId", requireSessionId(sessionId))
                .param("stateKey", requireKey(key))
                .update();
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return new HashSet<>(jdbc.sql("SELECT DISTINCT session_id FROM agent_state WHERE user_id = :userId")
                .param("userId", normalizeUser(userId))
                .query(String.class)
                .list());
    }

    private void upsert(String userId, String sessionId, String key, String kind, String stateJson) {
        jdbc.sql("""
                INSERT INTO agent_state
                  (user_id, session_id, state_key, state_kind, state_json, version, updated_at)
                VALUES (:userId, :sessionId, :stateKey, :stateKind, :stateJson, 1, :updatedAt)
                ON CONFLICT(user_id, session_id, state_key) DO UPDATE SET
                  state_kind = excluded.state_kind,
                  state_json = excluded.state_json,
                  version = agent_state.version + 1,
                  updated_at = excluded.updated_at
                """)
                .param("userId", normalizeUser(userId))
                .param("sessionId", requireSessionId(sessionId))
                .param("stateKey", requireKey(key))
                .param("stateKind", kind)
                .param("stateJson", stateJson)
                .param("updatedAt", Instant.now().toString())
                .update();
    }

    private Optional<StateRow> find(String userId, String sessionId, String key) {
        return jdbc.sql("""
                SELECT state_kind, state_json, version
                FROM agent_state
                WHERE user_id = :userId AND session_id = :sessionId AND state_key = :stateKey
                """)
                .param("userId", normalizeUser(userId))
                .param("sessionId", requireSessionId(sessionId))
                .param("stateKey", requireKey(key))
                .query((rs, rowNum) -> new StateRow(
                        rs.getString("state_kind"), rs.getString("state_json"), rs.getLong("version")))
                .optional();
    }

    private long findVersion(String userId, String sessionId, String key) {
        return jdbc.sql("""
                SELECT version FROM agent_state
                WHERE user_id = :userId AND session_id = :sessionId AND state_key = :stateKey
                """)
                .param("userId", userId)
                .param("sessionId", sessionId)
                .param("stateKey", key)
                .query(Long.class)
                .single();
    }

    private static String json(Object value) {
        Objects.requireNonNull(value, "state value must not be null");
        try {
            return JsonUtils.getJsonCodec().toJson(value);
        } catch (RuntimeException error) {
            throw new AgentStatePersistenceException("AgentState serialization failed", error);
        }
    }

    private static <T extends State> T decode(String value, Class<T> type, String sessionId) {
        try {
            return Objects.requireNonNull(
                    JsonUtils.getJsonCodec().fromJson(value, type), "state JSON decoded to null");
        } catch (RuntimeException error) {
            throw restoreFailure(sessionId, error);
        }
    }

    private static AgentStatePersistenceException restoreFailure(String sessionId, Throwable cause) {
        String message = "AgentState restore failed for session " + sessionId
                + "; refusing to create an empty runtime context";
        return cause == null ? new AgentStatePersistenceException(message) :
                new AgentStatePersistenceException(message, cause);
    }

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANONYMOUS_USER : userId;
    }

    private static String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId must not be blank");
        return sessionId;
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("state key must not be blank");
        return key;
    }

    private record StateRow(String kind, String json, long version) {
    }
}
