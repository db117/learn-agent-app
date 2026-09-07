package com.example.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.agent.persistence.SessionRecord;
import com.example.agent.persistence.SqliteRepository;
import com.example.agent.tool.EchoTool;
import com.example.agent.agent.TutorAgentService;
import com.google.adk.agents.LlmAgent;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.data-dir=target/context-test-data",
                "app.database=target/context-test-data/context.db"
        })
class AgentBackendApplicationTest {

    @Autowired
    private SqliteRepository repository;
    @Autowired
    private LlmAgent tutorAgent;
    @Autowired
    private ChatModel chatModel;
    @Autowired
    private EchoTool echoTool;
    @Autowired
    private TutorAgentService tutorAgentService;
    @Autowired
    private InMemorySessionService adkSessions;

    @Test
    void startsAgentAndReadsBackASessionFromSqlite() {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        repository.insertSession(new SessionRecord(id, "test-user", "context", now, now));

        assertNotNull(tutorAgent);
        assertNotNull(chatModel);
        assertEquals(Map.of("echo", "context"), echoTool.echo("context"));
        assertEquals(id, repository.findSession(id).orElseThrow().id());
    }

    @Test
    void restoresPersistedMessagesIntoANewAdkSession() {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        SessionRecord session = new SessionRecord(id, "test-user", "history", now, now);
        repository.insertSession(session);
        repository.insertMessage(new com.example.agent.persistence.MessageRecord(
                UUID.randomUUID().toString(), id, "user", "remember this", now));
        repository.insertMessage(new com.example.agent.persistence.MessageRecord(
                UUID.randomUUID().toString(), id, "assistant", "I remembered it", now.plusMillis(1)));

        tutorAgentService.ensureAdkSession(session);

        Session restored = adkSessions.getSession("desktop-learning-agent", "test-user", id, java.util.Optional.empty()).blockingGet();
        assertNotNull(restored);
        assertEquals(
                List.of("remember this", "I remembered it"),
                restored.events().stream().map(event -> event.stringifyContent()).toList());
    }
}
