package com.db117.learnagent.agent.runtime;

import com.db117.learnagent.agent.api.CreateTutorSessionRequest;
import com.db117.learnagent.agent.api.SendTutorMessageRequest;
import com.db117.learnagent.agent.api.TutorEventType;
import com.db117.learnagent.agent.application.TutorContextAssembler;
import com.db117.learnagent.agent.application.TutorRequestException;
import com.db117.learnagent.agent.application.TutorSessionService;
import com.db117.learnagent.learning.domain.*;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentScopeRuntimeTest {
    @Test
    void tutorProjectsVisibleTextAndRestoresSafeHistory() throws Exception {
        var root = Files.createTempDirectory("tutor-runtime");
        var model = new FakeModel();
        var runtime = new TutorAgentRuntime(() -> root.toString(), new TutorModel(model), root.resolve("agent"));
        var service = new TutorSessionService(
                new TutorContextAssembler(new FakeLearnerRepository(), new FakeJourneyRepository()), runtime);
        try {
            var session = service.createSession(new CreateTutorSessionRequest(1L, 1L));
            var events = service.streamTurn(
                            session.sessionId(),
                            new SendTutorMessageRequest("turn-1", "Explain this unit"))
                    .collectList()
                    .block();

            assertEquals(List.of(
                            TutorEventType.TURN_STARTED,
                            TutorEventType.ACTIVITY,
                            TutorEventType.ACTIVITY,
                            TutorEventType.MESSAGE_DELTA,
                            TutorEventType.TURN_COMPLETED),
                    events.stream().map(event -> event.type()).toList());
            assertEquals("visible answer", events.get(3).text());
            assertFalse(events.stream().anyMatch(event -> "private thought".equals(event.text())));

            var restored = service.createSession(new CreateTutorSessionRequest(1L, 1L));
            assertTrue(restored.restored());
            assertEquals(List.of("user", "assistant"),
                    restored.messages().stream().map(message -> message.role()).toList());
            assertEquals("visible answer", restored.messages().get(1).text());
            assertTrue(runtime.agent().getToolkit().getToolNames().isEmpty());

            var replay = service.streamTurn(
                            session.sessionId(),
                            new SendTutorMessageRequest("turn-1", "the text is intentionally ignored"))
                    .collectList()
                    .block();
            assertEquals(List.of(TutorEventType.TURN_STARTED, TutorEventType.ACTIVITY,
                            TutorEventType.MESSAGE_DELTA, TutorEventType.TURN_COMPLETED),
                    replay.stream().map(event -> event.type()).toList());
            assertEquals("visible answer", replay.get(2).text());
            assertEquals(1, model.calls.get());
        } finally {
            runtime.close();
        }
    }

    @Test
    void missingModelIsReportedWhenSendingRatherThanAtStartup() throws Exception {
        var root = Files.createTempDirectory("tutor-no-model");
        var runtime = new TutorAgentRuntime(() -> root.toString(), new TutorModel(null), root.resolve("agent"));
        var service = new TutorSessionService(
                new TutorContextAssembler(new FakeLearnerRepository(), new FakeJourneyRepository()), runtime);
        try {
            var session = service.createSession(new CreateTutorSessionRequest(1L, 1L));
            var error = assertThrows(TutorRequestException.class, () -> service.streamTurn(
                    session.sessionId(), new SendTutorMessageRequest("turn-1", "hello")));
            assertEquals("MODEL_UNAVAILABLE", error.code());
            assertEquals(503, error.status());
        } finally {
            runtime.close();
        }
    }

    @Test
    void modelFailurePersistsInputAndDoesNotRetryTheSameTurn() throws Exception {
        var root = Files.createTempDirectory("tutor-failure");
        var model = new FailingModel();
        var runtime = new TutorAgentRuntime(() -> root.toString(), new TutorModel(model), root.resolve("agent"));
        var service = new TutorSessionService(
                new TutorContextAssembler(new FakeLearnerRepository(), new FakeJourneyRepository()), runtime);
        try {
            var session = service.createSession(new CreateTutorSessionRequest(1L, 1L));
            var events = service.streamTurn(
                            session.sessionId(), new SendTutorMessageRequest("turn-fail", "please explain"))
                    .collectList()
                    .block();

            assertEquals(TutorEventType.TURN_FAILED, events.get(events.size() - 1).type());
            var restored = service.createSession(new CreateTutorSessionRequest(1L, 1L));
            assertEquals(List.of("user"), restored.messages().stream().map(message -> message.role()).toList());

            var replay = service.streamTurn(
                            session.sessionId(), new SendTutorMessageRequest("turn-fail", "retry is forbidden"))
                    .collectList()
                    .block();
            assertEquals(TutorEventType.TURN_FAILED, replay.get(replay.size() - 1).type());
            assertEquals(1, model.calls.get());
        } finally {
            runtime.close();
        }
    }

    private static final class FakeModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {
            calls.incrementAndGet();
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(ThinkingBlock.builder().thinking("private thought").build()))
                            .build(),
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("visible answer").build()))
                            .finishReason("stop")
                            .build());
        }

        @Override
        public String getModelName() {
            return "fake-tutor";
        }
    }

    private static final class FailingModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {
            calls.incrementAndGet();
            return Flux.error(new IllegalStateException("provider secret must stay private"));
        }

        @Override
        public String getModelName() {
            return "failing-tutor";
        }
    }

    private static final class FakeLearnerRepository implements LearnerRepository {
        private final Learner learner = new Learner(1L, "Ada", Instant.parse("2026-01-01T00:00:00Z"));

        @Override
        public Learner save(Learner learner) {
            return learner;
        }

        @Override
        public Optional<Learner> findById(long id) {
            return id == learner.id() ? Optional.of(learner) : Optional.empty();
        }
    }

    private static final class FakeJourneyRepository implements LearningJourneyRepository {
        private final LearningJourney journey = journey();

        @Override
        public LearningJourney save(LearningJourney journey) {
            return journey;
        }

        @Override
        public Optional<LearningJourney> findById(long id) {
            return id == journey.id() ? Optional.of(journey) : Optional.empty();
        }

        @Override
        public Optional<LearningJourney> findActiveByLearnerAndLanguage(long learnerId, String languagePackId) {
            return Optional.of(journey);
        }

        private static LearningJourney journey() {
            var at = Instant.parse("2026-01-01T00:00:00Z");
            var unit = LearnUnit.create("java-basics", "Java Basics", "Understand Java", "content", 0, "chapter-1", Set.of());
            var question = com.db117.learnagent.learning.domain.Question.singleChoice(
                    "q-1", "What language?", List.of("java", "go"), "java");
            var assessment = Assessment.create("java-basics", 70, List.of(question));
            return LearningJourney.reconstitute(
                    1L,
                    1L,
                    "java",
                    "Java Journey",
                    com.db117.learnagent.learning.domain.LearningJourneyStatus.ACTIVE,
                    at,
                    null,
                    List.of(Chapter.create("chapter-1", "Chapter 1", 0)),
                    List.of(unit),
                    List.of(assessment),
                    List.of(com.db117.learnagent.learning.domain.LearningPathItem.current("java-basics", 0, at)
                            .withId(1L)),
                    List.of());
        }
    }
}
