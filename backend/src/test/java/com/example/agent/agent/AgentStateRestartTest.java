package com.example.agent.agent;

import com.example.agent.persistence.SqliteAgentStateStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.sqlite.JDBC;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStateRestartTest {

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
    void recreatedHarnessRestoresTheSameConversationSlot() {
        SqliteAgentStateStore store = new SqliteAgentStateStore(dataSource);
        RuntimeContext context = RuntimeContext.builder()
                .userId("learner")
                .sessionId("session-a")
                .build();

        HarnessAgent first = agent(store, new DeterministicModel());
        first.streamEvents(List.of(new UserMessage("keep this")), context).collectList().block();
        first.close();

        DeterministicModel secondModel = new DeterministicModel();
        HarnessAgent recreated = agent(store, secondModel);
        recreated.streamEvents(List.of(new UserMessage("continue")), context).collectList().block();
        assertTrue(secondModel.requests.get(0).stream()
                .anyMatch(message -> "keep this".equals(message.getTextContent())));
        recreated.close();
    }

    private HarnessAgent agent(SqliteAgentStateStore store, DeterministicModel model) {
        return HarnessAgent.builder()
                .name("restart-tutor")
                .sysPrompt("You are a tutor.")
                .model(model)
                .stateStore(store)
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableTranscript()
                .disableWorkspaceContext()
                .disableSubagents()
                .disableDefaultWorkspaceSkills()
                .disableCompaction()
                .build();
    }

    private static final class DeterministicModel implements Model {

        private final List<List<Msg>> requests = new ArrayList<>();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            requests.add(List.copyOf(messages));
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("saved reply").build()))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "restart-test-model";
        }
    }
}
