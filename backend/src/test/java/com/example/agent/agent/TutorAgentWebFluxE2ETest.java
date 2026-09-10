package com.example.agent.agent;

import com.example.agent.api.SendMessageResponse;
import com.example.agent.api.SessionResponse;
import com.example.agent.persistence.TutorEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "app.data-dir=target/webflux-tutor-e2e-data",
                "app.database=target/webflux-tutor-e2e-data/tutor.db",
                "server.address=127.0.0.1",
                "server.port=18080",
                "app.openai.api-key=test-key",
                "app.openai.base-url=http://localhost"
        })
@Import(TutorAgentWebFluxE2ETest.DeterministicModelConfiguration.class)
class TutorAgentWebFluxE2ETest {

    private WebTestClient client;

    @Autowired
    private HarnessAgent tutorAgent;

    @Autowired
    private ClasspathSkillRepository skillRepository;

    @Autowired
    private Model model;

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
                .limitRate(1)
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
        assertEquals(1, events.stream().filter(TutorAgentWebFluxE2ETest::isTerminal).count());
    }

    @Test
    void assignsMonotonicSequencesAndReplaysOnlyEventsAfterLastEventId() {
        SessionResponse session = createSession("Replay events");
        client.post()
                .uri("/api/sessions/{id}/messages", session.id())
                .bodyValue(Map.of("content", "Replay this"))
                .exchange()
                .expectStatus().isOk();

        List<ServerSentEvent<TutorEvent>> initial = streamFramesUntilTerminal(session.id(), null);
        assertTrue(initial.size() >= 2);
        assertTrue(isStrictlyIncreasing(initial.stream().map(ServerSentEvent::data)
                .map(TutorEvent::sequence).toList()));
        assertEquals(initial.stream().map(frame -> Long.parseLong(frame.id())).toList(),
                initial.stream().map(ServerSentEvent::data).map(TutorEvent::sequence).toList());

        long lastEventId = initial.get(0).data().sequence();
        List<ServerSentEvent<TutorEvent>> replayed = streamFramesUntilTerminal(session.id(), lastEventId);

        assertEquals(initial.subList(1, initial.size()).stream().map(ServerSentEvent::data).toList(),
                replayed.stream().map(ServerSentEvent::data).toList());
        assertTrue(replayed.stream().allMatch(frame -> frame.data().sequence() > lastEventId));
    }

    @Test
    void discoversAndLoadsClasspathSkillWithSafeReasoningEvents() throws Exception {
        assertEquals(List.of("echo-verification"), skillRepository.getAllSkillNames());
        assertEquals(1, tutorAgent.getSkillRepositories().size());
        assertNotNull(tutorAgent.getToolkit().getTool("load_skill_through_path"));

        SessionResponse session = createSession("Skill events");
        client.post()
                .uri("/api/sessions/{id}/messages", session.id())
                .bodyValue(Map.of("content", "Load echo skill"))
                .exchange()
                .expectStatus().isOk();

        List<TutorEvent> events = streamUntilTerminal(session.id());

        assertTrue(events.stream().anyMatch(event -> event.eventType().equals("skill_load_start")));
        TutorEvent loaded = events.stream()
                .filter(event -> event.eventType().equals("skill_load_complete"))
                .findFirst()
                .orElseThrow();
        assertEquals("completed", loaded.status());
        assertTrue(loaded.skillName().contains("echo-verification"));
        assertTrue(events.stream().anyMatch(event -> event.eventType().equals("reasoning_summary")));
        assertTrue(events.stream().anyMatch(event -> event.eventType().equals("complete")));
        assertTrue(events.stream().noneMatch(event -> event.json().contains("PRIVATE_REASONING")));
        assertTrue(events.stream().noneMatch(event -> event.json().contains("Agent capability")));
        assertTrue(events.stream().noneMatch(event -> event.json().contains("rawJson")));
        assertFalse(new ObjectMapper().registerModule(new JavaTimeModule())
                .writeValueAsString(loaded).contains("rawJson"));
    }

    @Test
    void turnsClasspathSkillLoadFailureIntoTerminalProjectError() {
        SessionResponse session = createSession("Skill failure");
        client.post()
                .uri("/api/sessions/{id}/messages", session.id())
                .bodyValue(Map.of("content", "Load missing skill"))
                .exchange()
                .expectStatus().isOk();

        List<TutorEvent> events = streamUntilTerminal(session.id());
        TutorEvent error = events.stream()
                .filter(event -> event.eventType().equals("error"))
                .findFirst()
                .orElseThrow();

        assertEquals("failed", error.status());
        assertEquals("AgentScope Skill load failed", error.content());
        assertFalse(events.stream().anyMatch(event -> event.eventType().equals("complete")));
        assertTrue(events.stream().anyMatch(event -> event.eventType().equals("skill_load_start")));
        assertFalse(events.stream().anyMatch(event -> event.json().contains("missing-skill")));
        assertEquals(1, events.stream().filter(TutorAgentWebFluxE2ETest::isTerminal).count());
    }

    @Test
    void rejectsAnInvalidLastEventIdInsteadOfSilentlyChangingTheReplayWindow() {
        SessionResponse session = createSession("Invalid replay cursor");
        client.get()
                .uri("/api/sessions/{id}/events", session.id())
                .header("Last-Event-ID", "not-a-sequence")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void userCancellationStopsTheAgentScopeAndProviderStreamsWithOneTerminalEvent() throws Exception {
        DeterministicModel deterministic = (DeterministicModel) model;
        deterministic.prepareCancellation();
        SessionResponse session = createSession("User cancellation");
        SendMessageResponse sent = client.post()
                .uri("/api/sessions/{id}/messages", session.id())
                .bodyValue(Map.of("content", "cancel this"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SendMessageResponse.class)
                .returnResult()
                .getResponseBody();
        assertNotNull(sent);
        assertTrue(deterministic.awaitStarted());

        client.post()
                .uri("/api/sessions/{sessionId}/runs/{runId}/cancel", session.id(), sent.runId())
                .exchange()
                .expectStatus().isAccepted();

        assertTrue(deterministic.awaitCancelled());
        List<TutorEvent> events = streamUntilTerminal(session.id());
        assertEquals(1, events.stream().filter(TutorAgentWebFluxE2ETest::isTerminal).count());
        TutorEvent cancelled = events.stream()
                .filter(event -> event.eventType().equals("cancelled"))
                .findFirst()
                .orElseThrow();
        assertEquals("user_cancelled", cancelled.summary());
        assertFalse(events.stream().anyMatch(event -> event.eventType().equals("complete")));
        assertFalse(events.stream().anyMatch(event -> event.eventType().equals("error")));
    }

    @Test
    void clientDisconnectCancelsTheAgentWithoutRunningJdbcOnTheSseCaller() throws Exception {
        DeterministicModel deterministic = (DeterministicModel) model;
        deterministic.prepareCancellation();
        SessionResponse session = createSession("Client disconnect");
        client.post()
                .uri("/api/sessions/{id}/messages", session.id())
                .bodyValue(Map.of("content", "disconnect this"))
                .exchange()
                .expectStatus().isOk();

        Disposable connection = client.get()
                .uri("/api/sessions/{id}/events", session.id())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<TutorEvent>>() {})
                .getResponseBody()
                .subscribe();
        assertTrue(deterministic.awaitStarted());
        connection.dispose();

        assertTrue(deterministic.awaitCancelled());
        TutorEvent cancelled = streamUntilTerminal(session.id()).stream()
                .filter(event -> event.eventType().equals("cancelled"))
                .findFirst()
                .orElseThrow();
        assertEquals("client_disconnected", cancelled.summary());
    }

    @Test
    void providerFailureIsAStableRedactedErrorWithoutCompletion() {
        SessionResponse session = createSession("Provider failure");
        client.post()
                .uri("/api/sessions/{id}/messages", session.id())
                .bodyValue(Map.of("content", "provider failure"))
                .exchange()
                .expectStatus().isOk();

        List<TutorEvent> events = streamUntilTerminal(session.id());
        TutorEvent error = events.stream()
                .filter(event -> event.eventType().equals("error"))
                .findFirst()
                .orElseThrow();
        assertEquals("provider_error", error.summary());
        assertEquals("Model provider failed", error.content());
        assertFalse(error.json().contains("SECRET_PROMPT"));
        assertFalse(error.json().contains("api_key"));
        assertEquals(1, events.stream().filter(TutorAgentWebFluxE2ETest::isTerminal).count());
    }

    private SessionResponse createSession(String title) {
        return client.post()
                .uri("/api/sessions")
                .bodyValue(Map.of("title", title))
                .exchange()
                .expectStatus().isOk()
                .expectBody(SessionResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private List<TutorEvent> streamUntilTerminal(String sessionId) {
        return streamFramesUntilTerminal(sessionId, null).stream()
                .map(ServerSentEvent::data)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<ServerSentEvent<TutorEvent>> streamFramesUntilTerminal(String sessionId, Long lastEventId) {
        return client.get()
                .uri("/api/sessions/{id}/events", sessionId)
                .headers(headers -> {
                    if (lastEventId != null) headers.set("Last-Event-ID", Long.toString(lastEventId));
                })
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<TutorEvent>>() {})
                .getResponseBody()
                .filter(frame -> frame.data() != null)
                .takeUntil(frame -> frame.data().eventType().equals("complete")
                        || frame.data().eventType().equals("error")
                        || frame.data().eventType().equals("cancelled"))
                .collectList()
                .block(Duration.ofSeconds(5));
    }

    private boolean isStrictlyIncreasing(List<Long> values) {
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index) <= values.get(index - 1)) return false;
        }
        return true;
    }

    private static boolean isTerminal(TutorEvent event) {
        return event.eventType().equals("complete")
                || event.eventType().equals("error")
                || event.eventType().equals("cancelled");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DeterministicModelConfiguration {

        @Bean
        @Primary
        Model deterministicModel(ClasspathSkillRepository skillRepository) {
            return new DeterministicModel(skillRepository.getSkill("echo-verification").getSkillId());
        }
    }

    private static final class DeterministicModel implements Model {

        private final String skillId;
        private volatile CountDownLatch started = new CountDownLatch(0);
        private volatile CountDownLatch cancelled = new CountDownLatch(0);

        private DeterministicModel(String skillId) {
            this.skillId = skillId;
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {
            String request = messages.stream()
                    .filter(message -> message instanceof io.agentscope.core.message.UserMessage)
                    .map(Msg::getTextContent)
                    .reduce((first, second) -> second)
                    .orElse("");
            if (request.equals("Load echo skill") || request.equals("Load missing skill")) {
                boolean resultProvided = messages.stream()
                        .anyMatch(message -> !message.getContentBlocks(ToolResultBlock.class).isEmpty());
                if (!resultProvided) {
                    String requestedSkill = request.equals("Load echo skill") ? skillId : "missing-skill";
                    return Flux.just(skillRequest(requestedSkill));
                }
                return Flux.just(response("Skill loaded safely.", "stop"));
            }
            if (request.equals("cancel this") || request.equals("disconnect this")) {
                CountDownLatch cancelSignal = cancelled;
                started.countDown();
                ChatResponse progress = ChatResponse.builder()
                        .content(List.of(ThinkingBlock.builder().thinking("PRIVATE_REASONING").build()))
                        .build();
                return Flux.concat(
                        Flux.just(progress),
                        Flux.<ChatResponse>never().doOnCancel(cancelSignal::countDown));
            }
            if (request.equals("provider failure")) {
                return Flux.error(new ProviderFailure("SECRET_PROMPT api_key=hidden"));
            }
            return Flux.just(
                    response("Hello ", null),
                    response("AgentScope.", "stop"));
        }

        private void prepareCancellation() {
            started = new CountDownLatch(1);
            cancelled = new CountDownLatch(1);
        }

        private boolean awaitStarted() throws InterruptedException {
            return started.await(5, TimeUnit.SECONDS);
        }

        private boolean awaitCancelled() throws InterruptedException {
            return cancelled.await(5, TimeUnit.SECONDS);
        }

        @Override
        public String getModelName() {
            return "deterministic-tutor";
        }

        private ChatResponse skillRequest(String requestedSkill) {
            String arguments = "{\"skillId\":\"" + requestedSkill + "\",\"path\":\"SKILL.md\"}";
            List<ContentBlock> content = List.of(
                    ThinkingBlock.builder().thinking("PRIVATE_REASONING must never reach the UI").build(),
                    ToolUseBlock.builder()
                            .id("skill-call")
                            .name("load_skill_through_path")
                            .input(Map.of("skillId", requestedSkill, "path", "SKILL.md"))
                            .content(arguments)
                            .build());
            return ChatResponse.builder().content(content).finishReason("tool_calls").build();
        }

        private ChatResponse response(String text, String finishReason) {
            List<ContentBlock> content = List.of(TextBlock.builder().text(text).build());
            return ChatResponse.builder().content(content).finishReason(finishReason).build();
        }

        private static final class ProviderFailure extends RuntimeException {

            private ProviderFailure(String message) {
                super(message);
            }
        }
    }
}
