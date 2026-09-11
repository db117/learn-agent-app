package com.example.agent;

import com.example.agent.agent.TutorAgentService;
import com.example.agent.learning.assessment.Assessment;
import com.example.agent.learning.assessment.AssessmentAttempt;
import com.example.agent.learning.assessment.AssessmentStatus;
import com.example.agent.learning.assessment.AssessmentType;
import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionAttempt;
import com.example.agent.learning.assessment.QuestionRole;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.catalog.CurriculumGenerator;
import com.example.agent.learning.catalog.Chapter;
import com.example.agent.learning.catalog.CurriculumService;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.journey.LearningJourneyService;
import com.example.agent.learning.path.LearningPathItemStatus;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.progress.ProgressService;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.tutor.TutorSessionService;
import com.example.agent.persistence.AgentStatePersistenceException;
import com.example.agent.persistence.SessionRecord;
import com.example.agent.persistence.SqliteRepository;
import com.example.agent.tool.EchoTool;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.data-dir=target/context-test-data-learn-unit-v6",
                "app.database=target/context-test-data-learn-unit-v6/context.db",
                "app.openai.api-key=test-key",
                "app.openai.base-url=http://localhost"
        })
@Import(AgentBackendApplicationTest.TestCurriculumConfiguration.class)
class AgentBackendApplicationTest {

    @Autowired
    private SqliteRepository repository;
    @Autowired
    private HarnessAgent tutorAgent;
    @Autowired
    private ClasspathSkillRepository skillRepository;
    @Autowired
    private Model model;
    @Autowired
    private EchoTool echoTool;
    @Autowired
    private TutorAgentService tutorAgentService;
    @Autowired
    private LearningRepository learning;
    @Autowired
    private LearningJourneyService journeys;
    @Autowired
    private CurriculumService curriculum;
    @Autowired
    private ProgressService progress;
    @Autowired
    private TutorSessionService tutorSessions;
    @Autowired
    private AgentStateStore agentStateStore;
    @Autowired
    private DataSource dataSource;

    @Test
    void startsAgentAndReadsBackASessionFromSqlite() throws Exception {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        repository.insertSession(new SessionRecord(id, "test-user", "context", now, now));

        assertNotNull(tutorAgent);
        assertEquals(List.of("echo-verification"), skillRepository.getAllSkillNames());
        assertNotNull(skillRepository.getSkill("echo-verification"));
        assertNotNull(tutorAgent.getToolkit().getTool("echo"));
        assertNotNull(model);
        assertEquals(Map.of("echo", "context"), echoTool.echo("context"));
        assertEquals(id, repository.findSession(id).orElseThrow().id());
    }

    @Test
    void restoresPersistedMessagesForATutorSession() {
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
        var chapters = learning.listChaptersForJourney(journey.id());
        assertEquals(1, chapters.size());
        assertTrue(units.stream().allMatch(unit -> unit.chapterCode().equals(chapters.get(0).code())));

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
    void createsAndRestoresExactlyOneCurrentPathItemFromSqlite() {
        LearningJourney journey = journeys.create(
                "test-user", "typescript", "path restore", "Java", 8,
                "backend developer", "learn TypeScript");

        List<com.example.agent.learning.path.LearningPathItem> first = learning.listPath(journey.id());
        assertFalse(first.isEmpty());
        assertEquals(1, first.stream()
                .filter(item -> item.status() == LearningPathItemStatus.CURRENT).count());

        progress.generatePath(journey.id());

        LearningRepository restartedLearning = new LearningRepository(JdbcClient.create(dataSource));
        List<com.example.agent.learning.path.LearningPathItem> restored = restartedLearning.listPath(journey.id());
        assertEquals(first.size(), restored.size());
        assertEquals(1, restored.stream()
                .filter(item -> item.status() == LearningPathItemStatus.CURRENT).count());
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
        assertEquals(List.of("agent_state_restore_failed"), repository.listEvents(session.id()).stream()
                .filter(event -> event.eventType().equals("error"))
                .map(com.example.agent.persistence.TutorEvent::summary)
                .toList());
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
        assertEquals(LearningPathItemStatus.CURRENT, learning.findPathItem(journey.id(), current).orElseThrow().status());
        progress.recordLearnUnitAssessment(
                journey.id(), current, new AssessmentScore(100, 100, 90, true, true), true);
        assertEquals(LearningPathItemStatus.COMPLETED, learning.findPathItem(journey.id(), current).orElseThrow().status());
        assertTrue(learning.listWorkflowTransitions(journey.id()).size() >= 5);

        String skipped = learning.listPath(journey.id()).stream()
                .filter(item -> item.status() == LearningPathItemStatus.CURRENT)
                .findFirst().orElseThrow().learnUnitCode();
        progress.skipLearnUnit(journey.id(), skipped);
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

    @Test
    void restoresGeneratedContentAndPhaseStateFromSqlite() {
        LearningJourney journey = journeys.create(
                "test-user", "typescript", "content restore", "Java", 8,
                "backend developer", "learn TypeScript");
        String code = learning.listPath(journey.id()).get(0).learnUnitCode();

        LearnUnit generated = curriculum.ensureLearnUnitContent(journey.id(), code);
        assertTrue(generated.hasDetailedContent());
        assertEquals(1, learning.listQuestionsForLearnUnit(code).size());
        assertEquals(QuestionRole.INDEPENDENT, learning.listQuestionsForLearnUnit(code).get(0).role());

        progress.advancePhase(journey.id(), code, com.example.agent.learning.path.LearningPhase.EXPLANATION);
        LearningRepository restarted = new LearningRepository(JdbcClient.create(dataSource));
        var restored = restarted.findPathItem(journey.id(), code).orElseThrow();
        assertEquals(com.example.agent.learning.path.LearningPhase.EXAMPLE, restored.learningPhase());
        assertEquals(generated.guidedPracticePrompt(),
                restarted.findLearnUnit(code).orElseThrow().guidedPracticePrompt());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestCurriculumConfiguration {

        @Bean
        @Primary
        CurriculumGenerator curriculumGenerator() {
            return new CurriculumGenerator() {
                @Override
                public GeneratedOutline generateOutline(String requestedLanguage, String learningContext) {
                    String languageCode = requestedLanguage.trim().toLowerCase(Locale.ROOT);
                    String suffix = UUID.randomUUID().toString();
                    LearningLanguage language = new LearningLanguage(
                            "generated-language-" + suffix, languageCode, "TypeScript", "typed JavaScript", true);
                    Chapter chapter = new Chapter(
                            "generated-chapter-" + suffix, languageCode + ".fundamentals", "Fundamentals",
                            "Build the core language foundation", 1, List.of());
                    LearnUnit runtime = new LearnUnit(
                            "generated-learnUnit-runtime-" + suffix, languageCode, languageCode + ".javascript-runtime",
                            chapter.code(), "JavaScript Runtime", "Runtime fundamentals", 1, List.of(), 80, null, true,
                            List.of("Understand the runtime"), "", List.of("event loop"), List.of(), true);
                    LearnUnit types = new LearnUnit(
                            "generated-learnUnit-types-" + suffix, languageCode, languageCode + ".basic-types",
                            chapter.code(), "Basic Types", "Common types", 2, List.of(runtime.code()), 80, null, true,
                            List.of("Use common types"), "", List.of("unknown"), List.of(), true);
                    LearnUnit functions = new LearnUnit(
                            "generated-learnUnit-functions-" + suffix, languageCode, languageCode + ".functions",
                            chapter.code(), "Functions", "Function fundamentals", 3, List.of(types.code()), 80, null, true,
                            List.of("Write reusable functions"), "", List.of("parameters"), List.of(), true);
                    return new GeneratedOutline(List.of(language), List.of(chapter), List.of(runtime, types, functions));
                }

                @Override
                public GeneratedLearnUnitContent generateContent(LearnUnit outline, String learningContext) {
                    LearnUnit content = outline.withStructuredContent(
                            "使用并解释一个核心语言能力", 10, "先理解这个能力。", List.of("const answer = 42;"),
                            "写一个最小示例并说明结果。", List.of("先写最小代码"), "选择正确的核心概念。");
                    Question question = new Question(
                            "integration-independent-" + outline.code(), outline.code(), QuestionType.MULTIPLE_CHOICE,
                            1, "哪个选项符合本单元？", 20,
                            "{\"options\":[{\"id\":\"A\",\"text\":\"核心概念\"},{\"id\":\"B\",\"text\":\"无关概念\"}],\"correctOptionIds\":[\"A\"],\"multiple\":false}",
                            null, null, null, "[]", false);
                    return new GeneratedLearnUnitContent(content, List.of(question));
                }
            };
        }
    }
}
