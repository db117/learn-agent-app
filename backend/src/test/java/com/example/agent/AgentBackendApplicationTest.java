package com.example.agent;

import com.example.agent.agent.TutorAgentService;
import com.example.agent.learning.assessment.Assessment;
import com.example.agent.learning.assessment.AssessmentAttempt;
import com.example.agent.learning.assessment.AssessmentStatus;
import com.example.agent.learning.assessment.AssessmentType;
import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionAttempt;
import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.catalog.CurriculumGenerator;
import com.example.agent.learning.journey.LearnerSkillStatus;
import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.journey.LearningJourneyService;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.catalog.LearningSkill;
import com.example.agent.learning.path.LearningPathItemStatus;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.progress.ProgressService;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.persistence.SessionRecord;
import com.example.agent.persistence.SqliteRepository;
import com.example.agent.tool.EchoTool;
import com.google.adk.agents.LlmAgent;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
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
                "app.data-dir=target/context-test-data",
                "app.database=target/context-test-data/context.db"
        })
@Import(AgentBackendApplicationTest.TestCurriculumConfiguration.class)
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
    @Autowired
    private LearningRepository learning;
    @Autowired
    private LearningJourneyService journeys;
    @Autowired
    private ProgressService progress;

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

    @Test
    void persistsJourneyProgressAttemptsAndRetiredQuestionHistory() {
        LearningJourney journey = journeys.create(
                "test-user", "typescript", "integration", "Java", 8, "backend developer", "learn TypeScript");
        List<LearningSkill> skills = learning.listSkillsForJourney(journey.id());
        assertEquals(3, skills.size());
        LearningJourney otherJourney = journeys.create(
                "test-user", "typescript", "other integration", "Java", 8, "backend developer", "learn TypeScript differently");
        List<LearningSkill> otherSkills = learning.listSkillsForJourney(otherJourney.id());
        assertEquals(3, otherSkills.size());
        assertTrue(skills.stream().noneMatch(skill -> otherSkills.stream()
                .anyMatch(other -> other.code().equals(skill.code()))));
        String firstSkill = skills.get(0).code();
        progress.recordDiagnosticResult(
                journey.id(), firstSkill, new AssessmentScore(100, 100, 90, true, true), true);
        progress.generatePath(journey.id());

        var path = learning.listPath(journey.id());
        assertEquals(LearningPathItemStatus.COMPLETED, path.get(0).status());
        assertEquals(LearningPathItemStatus.CURRENT, path.get(1).status());
        String current = path.get(1).skillCode();
        progress.startSkill(journey.id(), current);
        progress.recordSkillAssessment(
                journey.id(), current, new AssessmentScore(100, 60, 70, true, true), false);
        assertEquals(LearnerSkillStatus.LEARNING, learning.findLearnerSkill(journey.id(), current).orElseThrow().status());
        progress.recordSkillAssessment(
                journey.id(), current, new AssessmentScore(100, 100, 90, true, true), true);
        assertEquals(LearnerSkillStatus.PASSED, learning.findLearnerSkill(journey.id(), current).orElseThrow().status());

        String skipped = learning.findJourney(journey.id()).orElseThrow().currentLearningSkillId();
        progress.skipSkill(journey.id(), skipped);
        assertEquals(LearnerSkillStatus.SKIPPED, learning.findLearnerSkill(journey.id(), skipped).orElseThrow().status());
        assertEquals(LearningPathItemStatus.SKIPPED, learning.findPathItem(journey.id(), skipped).orElseThrow().status());

        Question question = new Question(
                "integration-question-" + UUID.randomUUID(), current, QuestionType.MULTIPLE_CHOICE, 1,
                "Choose A", 20, "{\"correctOptionIds\":[\"A\"]}", null, null, null, "[]", false);
        learning.insertGeneratedQuestion(question);
        Assessment assessment = new Assessment(
                UUID.randomUUID().toString(), journey.id(), current, AssessmentType.SKILL,
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

        assertTrue(learning.listQuestionsForSkill(current).stream().noneMatch(value -> value.id().equals(question.id())));
        assertEquals(question.id(), learning.listQuestionsForAssessment(assessment.id()).get(0).id());
        assertEquals(1, learning.listAttemptsForSkill(journey.id(), current).size());
        assertEquals(1, learning.listQuestionAttemptsForSkill(journey.id(), current).size());
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
                LearningSkill runtime = new LearningSkill(
                        "generated-skill-runtime-" + suffix, languageCode, languageCode + ".javascript-runtime",
                        "JavaScript Runtime", "Runtime fundamentals", 1, List.of(), 80, 70, true,
                        List.of("Understand the runtime"), "Runtime lesson", List.of("event loop"),
                        List.of("Promise callbacks"), true);
                LearningSkill types = new LearningSkill(
                        "generated-skill-types-" + suffix, languageCode, languageCode + ".basic-types",
                        "Basic Types", "Common types", 2, List.of(runtime.code()), 80, 70, true,
                        List.of("Use common types"), "Types lesson", List.of("unknown"),
                        List.of("unknown at boundaries"), true);
                LearningSkill functions = new LearningSkill(
                        "generated-skill-functions-" + suffix, languageCode, languageCode + ".functions",
                        "Functions", "Function fundamentals", 3, List.of(types.code()), 80, 70, true,
                        List.of("Write reusable functions"), "Functions lesson", List.of("parameters"),
                        List.of("small functions"), true);
                return new CurriculumGenerator.GeneratedCurriculum(
                        List.of(language), List.of(runtime, types, functions));
            };
        }
    }
}
