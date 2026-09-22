package com.db117.learnagent.agent.runtime;

import com.db117.learnagent.agent.api.CreateTutorSessionRequest;
import com.db117.learnagent.agent.api.SendTutorMessageRequest;
import com.db117.learnagent.agent.api.TutorEventType;
import com.db117.learnagent.agent.application.TutorContextAssembler;
import com.db117.learnagent.agent.application.TutorRequestException;
import com.db117.learnagent.agent.application.TutorSessionService;
import com.db117.learnagent.agent.tool.TutorWorkspaceTools;
import com.db117.learnagent.config.RuntimeConfig;
import com.db117.learnagent.execution.LocalExecutionEnvironment;
import com.db117.learnagent.execution.TypeScriptCompiler;
import com.db117.learnagent.execution.TypeScriptTestRunner;
import com.db117.learnagent.language.LanguagePackCatalog;
import com.db117.learnagent.language.typescript.TypeScriptLanguagePack;
import com.db117.learnagent.learning.domain.Chapter;
import com.db117.learnagent.learning.domain.Journey;
import com.db117.learnagent.learning.domain.JourneyRepository;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.learning.domain.Learner;
import com.db117.learnagent.learning.domain.LearnerRepository;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningJourneyRepository;
import com.db117.learnagent.practice.application.PracticeRuntimeService;
import com.db117.learnagent.practice.domain.PracticeTask;
import com.db117.learnagent.practice.domain.PracticeTaskRepository;
import com.db117.learnagent.workspace.application.WorkspaceApplicationService;
import com.db117.learnagent.workspace.application.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentScopeRuntimeTest {
    @Test
    void memoryToolsPersistLearnerContextAcrossRuntimeSessions() throws Exception {
        Path root = Files.createTempDirectory("tutor-memory");
        TutorAgentRuntime runtime = new TutorAgentRuntime(
                testConfig(root, true),
                new TutorModel(new FakeModel()),
                root.resolve("agent"));
        try {
            com.db117.learnagent.agent.application.TutorContext tutorContext = contextAssembler(
                    new FakeLearnerRepository(),
                    new FakeParentJourneyRepository(),
                    new FakeJourneyRepository()).assemble(1L, 1L);
            ToolResultBlock saved = callTool(
                    runtime,
                    runtime.context("memory-session-1", "1", tutorContext),
                    "memory_save",
                    Map.of("content", "- 学习者偏好通过错误示例理解概念"));
            assertTrue(toolText(saved).contains("Saved 1 memory"), toolText(saved));

            runtime.close();
            runtime = new TutorAgentRuntime(
                    testConfig(root, true),
                    new TutorModel(new FakeModel()),
                    root.resolve("agent"));
            ToolResultBlock found = callTool(
                    runtime,
                    runtime.context("memory-session-2", "1", tutorContext),
                    "memory_search",
                    Map.of("query", "错误示例"));
            assertTrue(toolText(found).contains("学习者偏好通过错误示例理解概念"), toolText(found));
        } finally {
            runtime.close();
        }
    }

    @Test
    void tutorProjectsVisibleTextAndRestoresSafeHistory() throws Exception {
        Path root = Files.createTempDirectory("tutor-runtime");
        AgentScopeRuntimeTest.FakeModel model = new FakeModel();
        TutorAgentRuntime runtime = new TutorAgentRuntime(testConfig(root), new TutorModel(model), root.resolve("agent"));
        TutorSessionService service = new TutorSessionService(
                contextAssembler(
                        new FakeLearnerRepository(),
                        new FakeParentJourneyRepository(),
                        new FakeJourneyRepository()),
                runtime);
        try {
            com.db117.learnagent.agent.api.TutorSessionResponse session = service.createSession(new CreateTutorSessionRequest(1L, 1L));
            List<com.db117.learnagent.agent.api.TutorEvent> events = service.streamTurn(
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

            com.db117.learnagent.agent.api.TutorSessionResponse restored = service.createSession(new CreateTutorSessionRequest(1L, 1L));
            assertTrue(restored.restored());
            assertEquals(List.of("user", "assistant"),
                    restored.messages().stream().map(message -> message.role()).toList());
            assertEquals("visible answer", restored.messages().get(1).text());
            assertEquals(Set.of("load_skill_through_path"), runtime.agent().getToolkit().getToolNames());

            List<com.db117.learnagent.agent.api.TutorEvent> replay = service.streamTurn(
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
        Path root = Files.createTempDirectory("tutor-no-model");
        TutorAgentRuntime runtime = new TutorAgentRuntime(testConfig(root), new TutorModel(null), root.resolve("agent"));
        TutorSessionService service = new TutorSessionService(
                contextAssembler(
                        new FakeLearnerRepository(),
                        new FakeParentJourneyRepository(),
                        new FakeJourneyRepository()),
                runtime);
        try {
            com.db117.learnagent.agent.api.TutorSessionResponse session = service.createSession(new CreateTutorSessionRequest(1L, 1L));
            TutorRequestException error = assertThrows(TutorRequestException.class, () -> service.streamTurn(
                    session.sessionId(), new SendTutorMessageRequest("turn-1", "hello")));
            assertEquals("MODEL_UNAVAILABLE", error.code());
            assertEquals(503, error.status());
        } finally {
            runtime.close();
        }
    }

    @Test
    void modelFailurePersistsInputAndDoesNotRetryTheSameTurn() throws Exception {
        Path root = Files.createTempDirectory("tutor-failure");
        AgentScopeRuntimeTest.FailingModel model = new FailingModel();
        TutorAgentRuntime runtime = new TutorAgentRuntime(testConfig(root), new TutorModel(model), root.resolve("agent"));
        TutorSessionService service = new TutorSessionService(
                contextAssembler(
                        new FakeLearnerRepository(),
                        new FakeParentJourneyRepository(),
                        new FakeJourneyRepository()),
                runtime);
        try {
            com.db117.learnagent.agent.api.TutorSessionResponse session = service.createSession(new CreateTutorSessionRequest(1L, 1L));
            List<com.db117.learnagent.agent.api.TutorEvent> events = service.streamTurn(
                            session.sessionId(), new SendTutorMessageRequest("turn-fail", "please explain"))
                    .collectList()
                    .block();

            assertEquals(TutorEventType.TURN_FAILED, events.get(events.size() - 1).type());
            com.db117.learnagent.agent.api.TutorSessionResponse restored = service.createSession(new CreateTutorSessionRequest(1L, 1L));
            assertEquals(List.of("user"), restored.messages().stream().map(message -> message.role()).toList());

            List<com.db117.learnagent.agent.api.TutorEvent> replay = service.streamTurn(
                            session.sessionId(), new SendTutorMessageRequest("turn-fail", "retry is forbidden"))
                    .collectList()
                    .block();
            assertEquals(TutorEventType.TURN_FAILED, replay.get(replay.size() - 1).type());
            assertEquals(1, model.calls.get());
        } finally {
            runtime.close();
        }
    }

    @Test
    void planningSessionStartsBeforeLearningJourneyExists() throws Exception {
        Path root = Files.createTempDirectory("tutor-planning");
        TutorAgentRuntime runtime = new TutorAgentRuntime(testConfig(root), new TutorModel(null), root.resolve("agent"));
        TutorSessionService service = new TutorSessionService(
                contextAssembler(
                        new FakeLearnerRepository(),
                        new FakeParentJourneyRepository(2L),
                        new FakeJourneyRepository()),
                runtime);
        try {
            com.db117.learnagent.agent.api.TutorSessionResponse session = service.createSession(new CreateTutorSessionRequest(
                    1L, 2L, com.db117.learnagent.agent.api.TutorSessionMode.PLANNING));

            assertEquals(com.db117.learnagent.agent.api.TutorSessionMode.PLANNING, session.mode());
            assertNull(session.currentLearnUnitCode());
            assertFalse(session.restored());
        } finally {
            runtime.close();
        }
    }

    @Test
    void advancingTheCurrentLearnUnitCreatesANewSessionAndStalesTheOldOne() throws Exception {
        Path root = Files.createTempDirectory("tutor-session-rollover");
        AgentScopeRuntimeTest.MutableLearningJourneyRepository learningJourneys = new MutableLearningJourneyRepository(twoUnitJourney());
        TutorAgentRuntime runtime = new TutorAgentRuntime(testConfig(root), new TutorModel(null), root.resolve("agent"));
        TutorSessionService service = new TutorSessionService(
                contextAssembler(
                        new FakeLearnerRepository(),
                        new FakeParentJourneyRepository(),
                        learningJourneys),
                runtime);
        try {
            com.db117.learnagent.agent.api.TutorSessionResponse first = service.createSession(new CreateTutorSessionRequest(1L, 1L));
            com.db117.learnagent.agent.api.TutorSessionResponse restoredFirst = service.createSession(new CreateTutorSessionRequest(1L, 1L));
            assertEquals(first.sessionId(), restoredFirst.sessionId());
            assertEquals("first", first.currentLearnUnitCode());

            learningJourneys.journey = learningJourneys.journey
                    .recordPracticeVerified("first", Instant.parse("2026-01-01T00:00:01Z"));

            com.db117.learnagent.agent.api.TutorSessionResponse second = service.createSession(new CreateTutorSessionRequest(1L, 1L));
            assertNotEquals(first.sessionId(), second.sessionId());
            assertEquals("second", second.currentLearnUnitCode());

            TutorRequestException stale = assertThrows(TutorRequestException.class,
                    () -> service.streamTurn(first.sessionId(), new SendTutorMessageRequest("stale", "继续")));
            assertEquals("STALE_SESSION", stale.code());
        } finally {
            runtime.close();
        }
    }

    @Test
    void tutorDrivesRealLearningWorkspaceToolAndProjectsSafeEvents() throws Exception {
        Path dataDir = Files.createTempDirectory("tutor-workspace-runtime");
        RuntimeConfig config = new RuntimeConfig() {
            @Override
            public String dataDir() {
                return dataDir.toString();
            }

            @Override
            public boolean memoryEnabled() {
                return false;
            }
        };
        WorkspaceManager workspaces = new WorkspaceManager(config);
        AgentScopeRuntimeTest.FakeJourneyRepository learningJourneys = new FakeJourneyRepository("typescript");
        WorkspaceApplicationService workspaceAccess = new WorkspaceApplicationService(
                new FakeLearnerRepository(),
                new FakeParentJourneyRepository(),
                learningJourneys,
                null,
                typeScriptCatalog(),
                workspaces);
        LocalExecutionEnvironment environment = new LocalExecutionEnvironment();
        PracticeRuntimeService practiceRuntime = new PracticeRuntimeService(
                environment,
                new TypeScriptCompiler(environment),
                new TypeScriptTestRunner(environment),
                workspaces,
                new EmptyPracticeTaskRepository());
        TutorWorkspaceTools workspaceTools = new TutorWorkspaceTools(workspaces, workspaceAccess, practiceRuntime);
        AgentScopeRuntimeTest.ToolCallingModel model = new ToolCallingModel();
        TutorAgentRuntime runtime = new TutorAgentRuntime(
                config,
                new TutorModel(model),
                dataDir.resolve("agent"),
                workspaceTools);
        TutorSessionService service = new TutorSessionService(
                contextAssembler(
                        new FakeLearnerRepository(),
                        new FakeParentJourneyRepository(),
                        learningJourneys),
                runtime);
        try {
            com.db117.learnagent.agent.api.TutorSessionResponse session = service.createSession(new CreateTutorSessionRequest(1L, 1L));
            List<com.db117.learnagent.agent.api.TutorEvent> events = service.streamTurn(
                            session.sessionId(),
                            new SendTutorMessageRequest("tool-turn", "请先查看当前 Workspace"))
                    .collectList()
                    .block();

            assertTrue(model.sawWorkspaceToolSchema, model.toolSchemaText);
            assertTrue(model.sawLearningContext);
            assertTrue(model.sawToolResult, model.toolResultText + " schema=" + model.toolSchemaText);
            assertEquals(TutorEventType.TURN_STARTED, events.getFirst().type());
            assertEquals(TutorEventType.TURN_COMPLETED, events.getLast().type());
            assertTrue(events.stream().anyMatch(event -> event.type() == TutorEventType.TOOL_STARTED));
            assertTrue(events.stream().anyMatch(event -> event.type() == TutorEventType.TOOL_COMPLETED));
            assertTrue(events.stream().anyMatch(event -> event.type() == TutorEventType.MESSAGE_DELTA));
            assertTrue(events.stream().allMatch(event -> Set.of(
                    TutorEventType.TURN_STARTED,
                    TutorEventType.ACTIVITY,
                    TutorEventType.TOOL_STARTED,
                    TutorEventType.TOOL_COMPLETED,
                    TutorEventType.MESSAGE_DELTA,
                    TutorEventType.TURN_COMPLETED).contains(event.type())));
            assertEquals("已读取当前 Workspace。", events.stream()
                    .filter(event -> event.type() == TutorEventType.MESSAGE_DELTA)
                    .findFirst()
                    .orElseThrow()
                    .text());
            assertTrue(events.stream().noneMatch(event -> event.text() != null
                    && (event.text().contains("src/index.ts")
                    || event.text().contains("export {};")
                    || event.text().contains(dataDir.toString())
                    || event.text().contains("private thought")
                    || event.text().contains("provider-secret"))));
            assertTrue(events.stream().filter(event -> event.type() == TutorEventType.TOOL_STARTED)
                    .allMatch(event -> "已开始工具 read_file".equals(event.text())));
            assertTrue(events.stream().filter(event -> event.type() == TutorEventType.TOOL_COMPLETED)
                    .allMatch(event -> "已完成工具 read_file".equals(event.text())));
        } finally {
            runtime.close();
        }
    }

    private static LanguagePackCatalog typeScriptCatalog() throws Exception {
        Constructor<LanguagePackCatalog> constructor = LanguagePackCatalog.class.getDeclaredConstructor(Iterable.class);
        constructor.setAccessible(true);
        return constructor.newInstance(List.of(new TypeScriptLanguagePack()));
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

    private static final class ToolCallingModel implements Model {
        private int calls;
        private boolean sawWorkspaceToolSchema;
        private String toolSchemaText = "workspace tool schema was not observed";
        private boolean sawLearningContext;
        private boolean sawToolResult;
        private String toolResultText = "tool result was not observed";

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {
            if (calls++ == 0) {
                sawLearningContext = messages.stream()
                        .flatMap(message -> message.getContentBlocks(TextBlock.class).stream())
                        .anyMatch(block -> block.getText().contains("workspace-kind: LEARNING")
                                && block.getText().contains("workspace-id: 1"));
                Optional<ToolSchema> readFileSchema = tools.stream()
                        .filter(tool -> "read_file".equals(tool.getName()))
                        .findFirst();
                sawWorkspaceToolSchema = readFileSchema.isPresent();
                toolSchemaText = readFileSchema.map(schema -> schema.getParameters().toString())
                        .orElse("read_file schema was not observed");
                return Flux.just(ChatResponse.builder()
                        .content(List.of(ToolUseBlock.builder()
                                .id("list-files-1")
                                .name("read_file")
                                .input(java.util.Map.of("path", "src/index.ts"))
                                .content("{\"path\":\"src/index.ts\"}")
                                .build()))
                        .finishReason("tool_calls")
                        .build());
            }
            sawToolResult = messages.stream()
                    .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                    .filter(result -> "read_file".equals(result.getName()))
                    .peek(result -> toolResultText = result.getOutput().stream()
                            .filter(block -> block instanceof TextBlock)
                            .map(block -> ((TextBlock) block).getText())
                            .findFirst()
                            .orElse("tool result had no text"))
                    .anyMatch(result -> result.getOutput().stream().anyMatch(block -> block instanceof TextBlock
                            && ((TextBlock) block).getText().contains("export {};")));
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(ThinkingBlock.builder().thinking("private thought").build()))
                            .build(),
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("已读取当前 Workspace。").build()))
                            .finishReason("stop")
                            .build());
        }

        @Override
        public String getModelName() {
            return "tool-calling-tutor";
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

    private TutorContextAssembler contextAssembler(
            LearnerRepository learners,
            JourneyRepository journeys,
            LearningJourneyRepository learningJourneys) {
        return new TutorContextAssembler(
                learners,
                journeys,
                learningJourneys);
    }

    private static final class FakeLearnerRepository implements LearnerRepository {
        private final Learner learner = new Learner(
                1L, "Ada", "Java engineer with ten years of experience", Instant.parse("2026-01-01T00:00:00Z"));

        @Override
        public Learner save(Learner learner) {
            return learner;
        }

        @Override
        public Optional<Learner> findById(long id) {
            return id == learner.id() ? Optional.of(learner) : Optional.empty();
        }

        @Override
        public Optional<Learner> findCurrent() {
            return Optional.of(learner);
        }
    }

    private static final class FakeJourneyRepository implements LearningJourneyRepository {
        private final LearningJourney journey;

        private FakeJourneyRepository() {
            this("java");
        }

        private FakeJourneyRepository(String languagePackId) {
            journey = journey(languagePackId);
        }

        @Override
        public LearningJourney save(LearningJourney journey) {
            return journey;
        }

        @Override
        public void delete(long id) {
        }

        @Override
        public Optional<LearningJourney> findById(long id) {
            return id == journey.id() ? Optional.of(journey) : Optional.empty();
        }

        @Override
        public Optional<LearningJourney> findActiveByLearnerAndLanguage(long learnerId, String languagePackId) {
            return Optional.of(journey);
        }

        private static LearningJourney journey(String languagePackId) {
            Instant at = Instant.parse("2026-01-01T00:00:00Z");
            LearnUnit unit = LearnUnit.create("java-basics", "Java Basics", "Understand Java", "content", 0, "chapter-1", Set.of());
            return LearningJourney.reconstitute(
                    1L,
                    1L,
                    languagePackId,
                    "Java Journey",
                    com.db117.learnagent.learning.domain.LearningJourneyStatus.ACTIVE,
                    at,
                    null,
                    List.of(Chapter.create("chapter-1", "Chapter 1", 0)),
                    List.of(unit),
                    List.of(com.db117.learnagent.learning.domain.LearningPathItem.current("java-basics", 0, at)
                            .withId(1L)));
        }
    }

    private static final class MutableLearningJourneyRepository implements LearningJourneyRepository {
        private LearningJourney journey;

        private MutableLearningJourneyRepository(LearningJourney journey) {
            this.journey = journey;
        }

        @Override
        public LearningJourney save(LearningJourney journey) {
            this.journey = journey;
            return journey;
        }

        @Override
        public void delete(long id) {
        }

        @Override
        public Optional<LearningJourney> findById(long id) {
            return id == journey.id() ? Optional.of(journey) : Optional.empty();
        }

        @Override
        public Optional<LearningJourney> findActiveByLearnerAndLanguage(long learnerId, String languagePackId) {
            return Optional.of(journey);
        }
    }

    private static LearningJourney twoUnitJourney() {
        Instant at = Instant.parse("2026-01-01T00:00:00Z");
        LearnUnit first = LearnUnit.create(
                "first", "First", "Understand the first unit", "## Concept\nfirst\n\n## Example\none\n\n## Practice\nfirst practice",
                0, "chapter-1", Set.of());
        LearnUnit second = LearnUnit.create(
                "second", "Second", "Understand the second unit", "## Concept\nsecond\n\n## Example\ntwo\n\n## Practice\nsecond practice",
                1, "chapter-1", Set.of("first"));
        return LearningJourney.create(
                        1L,
                        "typescript",
                        "TypeScript Journey",
                        List.of(Chapter.create("chapter-1", "Chapter 1", 0)),
                        List.of(first, second),
                        at)
                .withId(1L);
    }

    private static RuntimeConfig testConfig(Path dataDir) {
        return testConfig(dataDir, false);
    }

    private static RuntimeConfig testConfig(Path dataDir, boolean memoryEnabled) {
        return new RuntimeConfig() {
            @Override
            public String dataDir() {
                return dataDir.toString();
            }

            @Override
            public boolean memoryEnabled() {
                return memoryEnabled;
            }
        };
    }

    private static ToolResultBlock callTool(
            TutorAgentRuntime runtime,
            io.agentscope.core.agent.RuntimeContext context,
            String name,
            Map<String, Object> input) {
        return runtime.agent().getToolkit().callTool(
                        ToolCallParam.builder()
                                .toolUseBlock(ToolUseBlock.builder()
                                        .id(name + "-test")
                                        .name(name)
                                        .input(input)
                                        .content(jsonInput(input))
                                        .build())
                                .input(input)
                                .runtimeContext(context)
                                .build())
                .block();
    }

    private static String jsonInput(Map<String, Object> input) {
        try {
            return new ObjectMapper().writeValueAsString(input);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static String toolText(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
    }

    private static final class EmptyPracticeTaskRepository implements PracticeTaskRepository {
        @Override
        public PracticeTask save(PracticeTask task) {
            return task;
        }

        @Override
        public Optional<PracticeTask> findById(long id) {
            return Optional.empty();
        }

        @Override
        public List<PracticeTask> findByLearnUnit(long journeyId, long learnUnitId) {
            return List.of();
        }
    }

    private static final class FakeParentJourneyRepository implements JourneyRepository {
        private final Journey journey;

        private FakeParentJourneyRepository() {
            this(1L);
        }

        private FakeParentJourneyRepository(long journeyId) {
            Journey created = Journey.create(
                    1L, "Build a Java service", Instant.parse("2026-01-01T00:00:00Z")).withId(journeyId);
            journey = journeyId == 1L ? created.attachLearningJourney(1L) : created;
        }

        @Override
        public Journey save(Journey journey) {
            return journey;
        }

        @Override
        public Optional<Journey> findById(long id) {
            return id == journey.id() ? Optional.of(journey) : Optional.empty();
        }

        @Override
        public List<Journey> findByLearnerId(long learnerId) {
            return learnerId == journey.learnerId() ? List.of(journey) : List.of();
        }

        @Override
        public Optional<Journey> findCurrentByLearnerId(long learnerId) {
            return Optional.empty();
        }

        @Override
        public Journey selectCurrent(long journeyId, long learnerId) {
            return journey.select();
        }

        @Override
        public Journey attachLearningJourney(long journeyId, long learningJourneyId, long learnerId) {
            return journey.attachLearningJourney(learningJourneyId);
        }
    }
}
