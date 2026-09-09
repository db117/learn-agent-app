package com.example.agent.persistence;

import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.sqlite.JDBC;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteAgentStateStoreTest {

    @TempDir
    Path tempDir;

    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SimpleDriverDataSource(
                new JDBC(), "jdbc:sqlite:" + tempDir.resolve("agent.db"));
        JdbcClient.create(dataSource).sql("""
                CREATE TABLE agent_state (
                    user_id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    state_key TEXT NOT NULL,
                    state_kind TEXT NOT NULL,
                    state_json TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (user_id, session_id, state_key)
                )
                """).update();
    }

    @Test
    void restoresAgentStateAfterTheStoreIsRecreated() {
        AgentState state = AgentState.builder()
                .userId("learner")
                .sessionId("session-a")
                .addMessage(new UserMessage("remember this"))
                .build();

        SqliteAgentStateStore first = new SqliteAgentStateStore(dataSource);
        first.save("learner", "session-a", "agent_state", state);
        first.close();

        SqliteAgentStateStore recreated = new SqliteAgentStateStore(dataSource);
        AgentState restored = recreated
                .get("learner", "session-a", "agent_state", AgentState.class)
                .orElseThrow();

        assertEquals("remember this", restored.getContext().get(0).getTextContent());
        assertEquals(state.getSessionId(), restored.getSessionId());
        assertEquals(state.getUserId(), restored.getUserId());
    }

    @Test
    void keepsAgentStateSlotsIsolatedBySessionIdentity() {
        SqliteAgentStateStore store = new SqliteAgentStateStore(dataSource);
        store.save("learner", "session-a", "agent_state", AgentState.builder()
                .sessionId("session-a")
                .addMessage(new UserMessage("A"))
                .build());
        store.save("learner", "session-b", "agent_state", AgentState.builder()
                .sessionId("session-b")
                .addMessage(new UserMessage("B"))
                .build());

        assertEquals("A", store.get("learner", "session-a", "agent_state", AgentState.class)
                .orElseThrow().getContext().get(0).getTextContent());
        assertEquals("B", store.get("learner", "session-b", "agent_state", AgentState.class)
                .orElseThrow().getContext().get(0).getTextContent());
        assertNotEquals(
                store.get("learner", "session-a", "agent_state", AgentState.class).orElseThrow().getSessionId(),
                store.get("learner", "session-b", "agent_state", AgentState.class).orElseThrow().getSessionId());
    }

    @Test
    void reportsCorruptStateInsteadOfReturningAnEmptyContext() {
        JdbcClient.create(dataSource).sql("""
                INSERT INTO agent_state
                  (user_id, session_id, state_key, state_kind, state_json, version, updated_at)
                VALUES ('learner', 'session-a', 'agent_state', 'single', 'not-json', 1, 'now')
                """).update();

        AgentStatePersistenceException error = assertThrows(
                AgentStatePersistenceException.class,
                () -> new SqliteAgentStateStore(dataSource)
                        .get("learner", "session-a", "agent_state", AgentState.class));

        assertTrue(error.getMessage().contains("AgentState restore failed"));
    }
}
