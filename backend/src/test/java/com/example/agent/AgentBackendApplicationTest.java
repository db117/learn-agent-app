package com.example.agent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.example.agent.agent.TutorAgentService;
import com.example.agent.learning.assessment.Assessment;
import com.example.agent.learning.assessment.AssessmentAttempt;
import com.example.agent.learning.assessment.AssessmentStatus;
import com.example.agent.learning.assessment.AssessmentType;
import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionAttempt;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.catalog.CurriculumGenerator;
import com.example.agent.learning.journey.LearnerLearnUnitStatus;
import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.journey.LearningJourneyService;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.path.LearningPathItemStatus;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.progress.ProgressService;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.tutor.TutorSessionService;
import com.example.agent.persistence.SessionRecord;
import com.example.agent.persistence.SqliteRepository;
import com.example.agent.persistence.AgentStatePersistenceException;
import com.example.agent.tool.EchoTool;
import io.agentscope.core.state.AgentStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.data-dir=target/context-test-data-learn-unit",
                "app.database=target/context-test-data-learn-unit/context.db",
                "spring.ai.openai.api-key=test-key",
                "spring.ai.openai.base-url=http://localhost"
        })
@Import(AgentBackendApplicationTest.TestCurriculumConfiguration.class)
class AgentBackendApplicationTest {

    @Autowired
    private SqliteRepository repository;
    @Autowired
    private ReactAgent saaTutorAgent;
    @Autowired
    private SkillRegistry agentSkillRegistry;
    @Autowired
    private CompiledGraph saaWorkflowGraph;
    @Autowired
    private ChatModel chatModel;
    @Autowired
    private EchoTool echoTool;
    @Autowired
    private TutorAgentService tutorAgentService;
    @Autowired
    private LearningRepository learning;
    @Autowired
    private LearningJourneyService journeys;
    @Autowired
    private ProgressService progress;
    @Autowired
    private TutorSessionService tutorSessions;
    @Autowired
    private AgentStateStore agentStateStore;

    @Test
    void startsAgentAndReadsBackASessionFromSqlite() throws Exception {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        repository.insertSession(new SessionRecord(id, "test-user", "context", now, now));

        assertNotNull(saaTutorAgent);
        assertEquals(1, agentSkillRegistry.size());
        assertTrue(agentSkillRegistry.contains("echo-verification"));
        assertTrue(agentSkillRegistry.readSkillContent("echo-verification").contains("Agent capability"));
        assertNotNull(saaWorkflowGraph);
        assertNotNull(chatModel);
        assertEquals(Map.of("echo", "context"), echoTool.echo("context"));
        assertEquals(id, repository.findSession(id).orElseThrow().id());
    }

    @Test
    void restoresPersistedMessagesForASaaSession() {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        SessionRecord session = new SessionRecord(id, "test-user", "history", now, now);
        repository.insertSession(session);
        repository.insertMessage(new com.example.agent.persistence.MessageRecord(
                UUID.randomUUID().toString(), id, "user", "remember this", now));
        repository.insertMessage(new com.example.agent.persistence.MessageRecord(
                UUID.randomUUID().toString(), id, "assistant", "I remembered it", now.plusMillis(1)));

        tutorAgentService.ensureSession(session);
        assertEquals(
                List.of("remember this", "I remembered it"),
                repository.listMessages(id).stream().map(message -> message.content()).toList());
    }

    @Test
    void reusesOneTutorSessionPerJourneyAndLearnUnit() {
        LearningJourney journey = journeys.create(
                "test-user", "typescript", "tutor session", "Java", 8,
                "backend developer", "learn TypeScript");
        List<LearnUnit> units = learning.listLearnUnitsForJourney(journey.id());

        TutorSessionService.TutorSession first = tutorSessions.open(journey.id(), units.get(0).code());
        TutorSessionService.TutorSession reentered = tutorSessions.open(journey.id(), units.get(0).code());
        TutorSessionService.TutorSession switched = tutorSessions.open(journey.id(), units.get(1).code());

        assertEquals(first.session().id(), reentered.session().id());
        assertNotNull(switched.session());
        org.junit.jupiter.api.Assertions.assertNotEquals(first.session().id(), switched.session().id());
        assertEquals(first.session().id(), learning.findTutorSessionId(journey.id(), units.get(0).code()).orElseThrow());
        assertEquals(switched.session().id(), learning.findTutorSessionId(journey.id(), units.get(1).code()).orElseThrow());
        assertEquals(journey.id(), learning.findJourney(journey.id()).orElseThrow().id());
    }

    @Test
    void refusesToStartWhenConversationExistsWithoutAgentState() {
        LearningJourney journey = journeys.create(
                "test-user", "typescript", "state separation", "Java", 8,
                "backend developer", "learn TypeScript");
        String learnUnitCode = learning.listLearnUnitsForJourney(journey.id()).get(0).code();
        SessionRecord session = tutorSessions.open(journey.id(), learnUnitCode).session();
        Instant now = Instant.now();
        repository.insertMessage(new com.example.agent.persistence.MessageRecord(
                UUID.randomUUID().toString(), session.id(), "user", "old message", now));
        LearningJourney before = journeys.get(journey.id());

        AgentStatePersistenceException error = org.junit.jupiter.api.Assertions.assertThrows(
                AgentStatePersistenceException.class,
                () -> tutorAgentService.start(session, "new message"));

        assertTrue(error.getMessage().contains("AgentState restore failed"));
        assertEquals(1, repository.listMessages(session.id()).size());
        assertTrue(!agentStateStore.exists(session.userId(), session.id()));
        assertEquals(before, journeys.get(journey.id()));
    }

    @Test
    void persistsJourneyProgressAttemptsAndRetiredQuestionHistory() {
        LearningJourney journey = journeys.create(
                "test-user", "typescript", "integration", "Java", 8, "backend developer", "learn TypeScript");
        List<LearnUnit> learnUnits = learning.listLearnUnitsForJourney(journey.id());
        assertEquals(3, learnUnits.size());
        LearningJourney otherJourney = journeys.create(
                "test-user", "typescript", "other integration", "Java", 8, "backend developer", "learn TypeScript differently");
        List<LearnUnit> otherLearnUnits = learning.listLearnUnitsForJourney(otherJourney.id());
        assertEquals(3, otherLearnUnits.size());
        assertTrue(learnUnits.stream().noneMatch(learnUnit -> otherLearnUnits.stream()
                .anyMatch(other -> other.code().equals(learnUnit.code()))));
        String firstLearnUnit = learnUnits.get(0).code();
        progress.recordDiagnosticResult(
                journey.id(), firstLearnUnit, new AssessmentScore(100, 100, 90, true, true), true);
        progress.generatePath(journey.id());

        var path = learning.listPath(journey.id());
        assertEquals(LearningPathItemStatus.COMPLETED, path.get(0).status());
        assertEquals(LearningPathItemStatus.CURRENT, path.get(1).status());
        String current = path.get(1).learnUnitCode();
        progress.startLearnUnit(journey.id(), current);
        progress.recordLearnUnitAssessment(
                journey.id(), current, new AssessmentScore(100, 60, 70, true, true), false);
        assertEquals(LearnerLearnUnitStatus.LEARNING, learning.findLearnerLearnUnit(journey.id(), current).orElseThrow().status());
        progress.recordLearnUnitAssessment(
                journey.id(), current, new AssessmentScore(100, 100, 90, true, true), true);
        assertEquals(LearnerLearnUnitStatus.PASSED, learning.findLearnerLearnUnit(journey.id(), current).orElseThrow().status());
        assertTrue(learning.listWorkflowTransitions(journey.id()).size() >= 5);

        String skipped = learning.findJourney(journey.id()).orElseThrow().currentLearnUnitCode();
        progress.skipLearnUnit(journey.id(), skipped);
        assertEquals(LearnerLearnUnitStatus.SKIPPED, learning.findLearnerLearnUnit(journey.id(), skipped).orElseThrow().status());
        assertEquals(LearningPathItemStatus.SKIPPED, learning.findPathItem(journey.id(), skipped).orElseThrow().status());

        Question question = new Question(
                "integration-question-" + UUID.randomUUID(), current, QuestionType.MULTIPLE_CHOICE, 1,
                "Choose A", 20, "{\"correctOptionIds\":[\"A\"]}", null, null, null, "[]", false);
        learning.insertGeneratedQuestion(question);
        Assessment assessment = new Assessment(
                UUID.randomUUID().toString(), journey.id(), current, AssessmentType.LEARN_UNIT,
                AssessmentStatus.COMPLETED, Instant.now(), Instant.now());
        learning.insertAssessment(assessment);
        learning.insertAssessmentQuestion(assessment.id(), question.id(), 0);
        AssessmentAttempt attempt = new AssessmentAttempt(
                UUID.randomUUID().toString(), assessment.id(), journey.id(), current, 1,
                0, null, 0, false, Instant.now(), Instant.now());
        learning.insertAttempt(attempt);
        learning.saveQuestionAttempt(new QuestionAttempt(
                question.id(), attempt.id(), "{}", 0, 20, "wrong", false, null, null, "[]"));
        learning.retireQuestion(question.id());

        assertTrue(learning.listQuestionsForLearnUnit(current).stream().noneMatch(value -> value.id().equals(question.id())));
        assertEquals(question.id(), learning.listQuestionsForAssessment(assessment.id()).get(0).id());
        assertEquals(1, learning.listAttemptsForLearnUnit(journey.id(), current).size());
        assertEquals(1, learning.listQuestionAttemptsForLearnUnit(journey.id(), current).size());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestCurriculumConfiguration {

        @Bean
        @Primary
        CurriculumGenerator curriculumGenerator() {
            return (requestedLanguage, learningContext) -> {
                String languageCode = requestedLanguage.trim().toLowerCase(Locale.ROOT);
                String suffix = UUID.randomUUID().toString();
                LearningLanguage language = new LearningLanguage(
                        "generated-language-" + suffix, languageCode, "TypeScript", "typed JavaScript", true);
                LearnUnit runtime = new LearnUnit(
                        "generated-learnUnit-runtime-" + suffix, languageCode, languageCode + ".javascript-runtime",
                        "JavaScript Runtime", "Runtime fundamentals", 1, List.of(), 80, 70, true,
                        List.of("Understand the runtime"), "Runtime lesson", List.of("event loop"),
                        List.of("Promise callbacks"), true);
                LearnUnit types = new LearnUnit(
                        "generated-learnUnit-types-" + suffix, languageCode, languageCode + ".basic-types",
                        "Basic Types", "Common types", 2, List.of(runtime.code()), 80, 70, true,
                        List.of("Use common types"), "Types lesson", List.of("unknown"),
                        List.of("unknown at boundaries"), true);
                LearnUnit functions = new LearnUnit(
                        "generated-learnUnit-functions-" + suffix, languageCode, languageCode + ".functions",
                        "Functions", "Function fundamentals", 3, List.of(types.code()), 80, 70, true,
                        List.of("Write reusable functions"), "Functions lesson", List.of("parameters"),
                        List.of("small functions"), true);
                return new CurriculumGenerator.GeneratedCurriculum(
                        List.of(language), List.of(runtime, types, functions));
            };
        }
    }
}
