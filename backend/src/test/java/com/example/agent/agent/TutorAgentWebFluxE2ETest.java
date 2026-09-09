package com.example.agent.agent;

import com.example.agent.api.SendMessageResponse;
import com.example.agent.api.SessionResponse;
import com.example.agent.persistence.TutorEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "app.data-dir=target/webflux-tutor-e2e-data",
                "app.database=target/webflux-tutor-e2e-data/tutor.db",
                "server.address=127.0.0.1",
                "server.port=18080",
                "spring.ai.openai.api-key=test-key",
                "spring.ai.openai.base-url=http://localhost"
        })
@Import(TutorAgentWebFluxE2ETest.DeterministicModelConfiguration.class)
class TutorAgentWebFluxE2ETest {

    private WebTestClient client;

    @Autowired
    private HarnessAgent tutorAgent;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:18080").build();
    }

    @Test
    void streamsTutorTextDeltasAndCompletionOverProjectOwnedSseEvents() {
        assertNotNull(tutorAgent);

        SessionResponse session = client.post()
                .uri("/api/sessions")
                .bodyValue(Map.of("title", "WebFlux tutor"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SessionResponse.class)
                .returnResult()
                .getResponseBody();
        assertNotNull(session);

        SendMessageResponse sent = client.post()
                .uri("/api/sessions/{id}/messages", session.id())
                .bodyValue(Map.of("content", "Explain streams"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SendMessageResponse.class)
                .returnResult()
                .getResponseBody();
        assertNotNull(sent);

        List<TutorEvent> events = client.get()
                .uri("/api/sessions/{id}/events", session.id())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<TutorEvent>>() {})
                .getResponseBody()
                .map(ServerSentEvent::data)
                .filter(Objects::nonNull)
                .take(3)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertEquals(List.of("text_delta", "text_delta", "complete"),
                events.stream().map(TutorEvent::eventType).toList());
        assertEquals("Hello AgentScope.", events.stream()
                .filter(event -> event.eventType().equals("text_delta"))
                .map(TutorEvent::content)
                .reduce("", String::concat));
        assertEquals(sent.runId(), events.get(0).runId());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DeterministicModelConfiguration {

        @Bean
        @Primary
        Model deterministicModel() {
            return new DeterministicModel();
        }
    }

    private static final class DeterministicModel implements Model {

        @Override
        public Flux<ChatResponse> stream(
                List<io.agentscope.core.message.Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {
            return Flux.just(
                    response("Hello ", null),
                    response("AgentScope.", "stop"));
        }

        @Override
        public String getModelName() {
            return "deterministic-tutor";
        }

        private ChatResponse response(String text, String finishReason) {
            List<ContentBlock> content = List.of(TextBlock.builder().text(text).build());
            return ChatResponse.builder().content(content).finishReason(finishReason).build();
        }
    }
}
