package com.example.agent.learning.assessment;

import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.diagnostic.DiagnosticQuestionPlanner;
import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.progress.ProgressService;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.scoring.AssessmentScoreEngine;
import com.example.agent.learning.scoring.LearnUnitPassPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssessmentServiceTest {

    private final LearningRepository repository = mock(LearningRepository.class);
    private final ProgressService progress = mock(ProgressService.class);
    private final DiagnosticQuestionPlanner planner = mock(DiagnosticQuestionPlanner.class);
    private final AssessmentService service = new AssessmentService(
            repository, progress, new AssessmentScoreEngine(), new LearnUnitPassPolicy(),
            (question, submittedCode) -> submittedCode == null || submittedCode.isBlank()
                    ? new CodingEvaluationResult(0, 0, 0, "Submit an implementation to receive feedback.", List.of("Code is empty"))
                    : new CodingEvaluationResult(60, 20, 20, "Deterministic test evaluation passed.", List.of()),
            planner);

    @Test
    void failedLearnUnitAssessmentCanRetryWithoutOverwritingTheFirstAttempt() {
        Assessment assessment = new Assessment(
                "assessment", "journey", "learnUnit-a", AssessmentType.LEARN_UNIT,
                AssessmentStatus.IN_PROGRESS, Instant.EPOCH, null);
        LearnUnit learnUnit = new LearnUnit(
                "learnUnit-a", "typescript", "learnUnit-a", "chapter", "LearnUnit A", "description", 1, List.of(),
                80, 70, true, List.of("objective"), "intro", List.of("concept"), List.of("example"), true);
        Question choice = new Question(
                "choice", "learnUnit-a", QuestionType.MULTIPLE_CHOICE, 1, "Choose", 20,
                "{\"correctOptionIds\":[\"A\"]}", null, null, null, "[]", false);
        Question coding = new Question(
                "coding", "learnUnit-a", QuestionType.CODING, 1, "Implement", 100,
                null, "{\"correctness\":60,\"languageUsage\":20,\"clarity\":20}",
                "typescript", "", "[]", false);
        AssessmentAttempt first = attempt("attempt-1");
        AssessmentAttempt second = attempt("attempt-2");
        when(repository.findAssessment("assessment")).thenReturn(Optional.of(assessment));
        when(repository.findOpenAttempt("assessment")).thenReturn(Optional.of(first), Optional.of(second));
        when(repository.listQuestionsForAssessment("assessment")).thenReturn(List.of(choice, coding));
        when(repository.findLearnUnit("learnUnit-a")).thenReturn(Optional.of(learnUnit));
        when(repository.listQuestionAttempts("attempt-1")).thenReturn(List.of(
                new QuestionAttempt("choice", "attempt-1", "{}", 0, 20, "wrong", false, null, null, "[]"),
                new QuestionAttempt("coding", "attempt-1", "{}", null, 100, null, null, "answer", null, null)));
        when(repository.listQuestionAttempts("attempt-2")).thenReturn(List.of(
                new QuestionAttempt("choice", "attempt-2", "{}", 20, 20, "Correct.", true, null, null, "[\"A\"]"),
                new QuestionAttempt("coding", "attempt-2", "{}", null, 100, null, null, "answer", null, null)));
        when(repository.findAttempt("attempt-1")).thenReturn(Optional.of(first));
        when(repository.findAttempt("attempt-2")).thenReturn(Optional.of(second));

        AssessmentService.AssessmentSubmission failed = service.submit("assessment");
        AssessmentService.AssessmentSubmission passed = service.submit("assessment");

        assertFalse(failed.passed());
        assertEquals(60, failed.score().totalScore());
        assertTrue(passed.passed());
        assertEquals(100, passed.score().totalScore());
        verify(repository).updateAttempt(eq("attempt-1"), eq(0), eq(100), eq(60), eq(false), any(Instant.class));
        verify(repository).updateAttempt(eq("attempt-2"), eq(100), eq(100), eq(100), eq(true), any(Instant.class));
        verify(progress).recordLearnUnitAssessment(
                "journey", "learnUnit-a", new AssessmentScore(0, 100, 60, true, true), false);
        verify(progress).recordLearnUnitAssessment(
                "journey", "learnUnit-a", new AssessmentScore(100, 100, 100, true, true), true);
    }

    @Test
    void retryUsesTheExistingAssessmentAndFixedQuestionSet() {
        Assessment completed = new Assessment(
                "assessment", "journey", "learnUnit-a", AssessmentType.LEARN_UNIT,
                AssessmentStatus.COMPLETED, Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
        Assessment inProgress = new Assessment(
                "assessment", "journey", "learnUnit-a", AssessmentType.LEARN_UNIT,
                AssessmentStatus.IN_PROGRESS, Instant.EPOCH, completed.completedAt());
        AssessmentAttempt failed = new AssessmentAttempt(
                "attempt-1", completed.id(), completed.journeyId(), completed.learnUnitCode(), 1,
                60, null, 60, false, Instant.EPOCH, completed.completedAt());
        AssessmentAttempt retry = new AssessmentAttempt(
                "attempt-2", completed.id(), completed.journeyId(), completed.learnUnitCode(), 2,
                null, null, null, null, Instant.EPOCH, null);
        Question choice = new Question(
                "choice", "learnUnit-a", QuestionType.MULTIPLE_CHOICE, 1, "Choose", 20,
                "{\"correctOptionIds\":[\"A\"]}", null, null, null, "[]", false);
        when(repository.findAssessment(completed.id())).thenReturn(Optional.of(completed), Optional.of(inProgress));
        when(repository.findLatestLearnUnitAssessment(completed.journeyId(), completed.learnUnitCode()))
                .thenReturn(Optional.of(completed));
        when(repository.findOpenAttempt(completed.id())).thenReturn(Optional.empty(), Optional.empty(), Optional.of(retry));
        when(repository.listQuestionsForAssessment(completed.id())).thenReturn(List.of(choice));
        when(repository.listAttemptsForAssessment(completed.id())).thenReturn(List.of(failed));
        when(repository.nextAttemptNumber(completed.id())).thenReturn(2);
        when(repository.listQuestionAttempts(retry.id())).thenReturn(List.of());

        AssessmentService.AssessmentState result = service.retry(completed.journeyId(), completed.learnUnitCode());

        assertEquals(inProgress, result.assessment());
        assertEquals(List.of(choice), result.questions());
        assertEquals(retry, result.openAttempt());
        verify(progress).requireCurrentLearnUnit(completed.journeyId(), completed.learnUnitCode());
        verify(repository).insertAttempt(argThat(attempt -> attempt.assessmentId().equals(completed.id())
                && attempt.attemptNumber() == 2 && attempt.completedAt() == null));
        verify(repository, never()).insertAssessmentQuestion(anyString(), anyString(), any(Integer.class));
    }

    @Test
    void multipleChoiceUsesTheExactConfiguredOptionSet() {
        Question question = new Question(
                "choice", "learnUnit-a", QuestionType.MULTIPLE_CHOICE, 1, "Choose", 20,
                "{\"options\":[{\"id\":\"A\",\"text\":\"yes\"},{\"id\":\"B\",\"text\":\"no\"}],"
                        + "\"correctOptionIds\":[\"A\",\"B\"],\"multiple\":true}",
                null, null, null, "[]", false);
        MultipleChoiceEvaluator evaluator = new MultipleChoiceEvaluator();

        assertEquals(new MultipleChoiceEvaluator.Result(20, true), evaluator.evaluate(question, List.of("B", "A")));
        assertEquals(new MultipleChoiceEvaluator.Result(0, false), evaluator.evaluate(question, List.of("A")));
        assertEquals(new MultipleChoiceEvaluator.Result(0, false), evaluator.evaluate(question, List.of("A", "A", "B")));
    }

    @Test
    void choiceOnlyAssessmentDoesNotRequireCodingScore() {
        Assessment assessment = new Assessment(
                "assessment", "journey", "learnUnit-a", AssessmentType.LEARN_UNIT,
                AssessmentStatus.IN_PROGRESS, Instant.EPOCH, null);
        LearnUnit learnUnit = new LearnUnit(
                "learnUnit-a", "reading", "learnUnit-a", "chapter", "LearnUnit A", "description", 1, List.of(),
                80, 70, true, List.of("objective"), "intro", List.of("concept"), List.of("example"), true);
        AssessmentAttempt openAttempt = attempt("attempt-1");
        Question choice = new Question(
                "choice", "learnUnit-a", QuestionType.MULTIPLE_CHOICE, 1, "Choose", 20,
                "{\"correctOptionIds\":[\"A\"]}", null, null, null, "[]", false);
        when(repository.findAssessment("assessment")).thenReturn(Optional.of(assessment));
        when(repository.findOpenAttempt("assessment")).thenReturn(Optional.of(openAttempt));
        when(repository.listQuestionsForAssessment("assessment")).thenReturn(List.of(choice));
        when(repository.listQuestionAttempts("attempt-1")).thenReturn(List.of(
                new QuestionAttempt("choice", "attempt-1", "{}", 16, 20, "Correct.", true, null, null, "[\"A\"]")));
        when(repository.findLearnUnit("learnUnit-a")).thenReturn(Optional.of(learnUnit));
        when(repository.findAttempt("attempt-1")).thenReturn(Optional.of(new AssessmentAttempt(
                "attempt-1", "assessment", "journey", "learnUnit-a", 1,
                80, null, 80, true, Instant.EPOCH, Instant.now())));

        AssessmentService.AssessmentSubmission result = service.submit("assessment");

        assertTrue(result.passed());
        assertEquals(80, result.score().totalScore());
        assertFalse(result.score().hasCodingQuestions());
        assertEquals(80, result.passScore());
        assertNull(result.codingPassScore());
        verify(progress).recordLearnUnitAssessment(
                "journey", "learnUnit-a", new AssessmentScore(80, 0, 80, true, false), true);
        verify(repository).updateAttempt(eq("attempt-1"), eq(80), eq(0), eq(80), eq(true), any(Instant.class));
    }

    @Test
    void codingOnlyAssessmentUsesCodingScoreAndThreshold() {
        Assessment assessment = new Assessment(
                "assessment", "journey", "learnUnit-a", AssessmentType.LEARN_UNIT,
                AssessmentStatus.IN_PROGRESS, Instant.EPOCH, null);
        LearnUnit learnUnit = new LearnUnit(
                "learnUnit-a", "typescript", "learnUnit-a", "chapter", "LearnUnit A", "description", 1, List.of(),
                80, 70, true, List.of("objective"), "intro", List.of("concept"), List.of("example"), true);
        AssessmentAttempt openAttempt = attempt("attempt-1");
        Question coding = new Question(
                "coding", "learnUnit-a", QuestionType.CODING, 1, "Implement", 100,
                null, "{\"correctness\":60,\"languageUsage\":20,\"clarity\":20}",
                "typescript", "", "[]", false);
        when(repository.findAssessment("assessment")).thenReturn(Optional.of(assessment));
        when(repository.findOpenAttempt("assessment")).thenReturn(Optional.of(openAttempt));
        when(repository.listQuestionsForAssessment("assessment")).thenReturn(List.of(coding));
        when(repository.listQuestionAttempts("attempt-1")).thenReturn(List.of(
                new QuestionAttempt("coding", "attempt-1", "{}", 0, 100, "draft", null, "answer", null, null)));
        when(repository.findLearnUnit("learnUnit-a")).thenReturn(Optional.of(learnUnit));
        when(repository.findAttempt("attempt-1")).thenReturn(Optional.of(new AssessmentAttempt(
                "attempt-1", "assessment", "journey", "learnUnit-a", 1,
                0, 100, 100, true, Instant.EPOCH, Instant.now())));

        AssessmentService.AssessmentSubmission result = service.submit("assessment");

        assertTrue(result.passed());
        assertEquals(100, result.score().codingScore());
        assertEquals(100, result.score().totalScore());
        assertFalse(result.score().hasChoiceQuestions());
        assertEquals(80, result.passScore());
        assertEquals(70, result.codingPassScore());
        verify(progress).recordLearnUnitAssessment(
                "journey", "learnUnit-a", new AssessmentScore(0, 100, 100, false, true), true);
        verify(repository).updateAttempt(eq("attempt-1"), eq(0), eq(100), eq(100), eq(true), any(Instant.class));
    }

    @Test
    void codingEvaluationIsScaledToQuestionMaximum() {
        AssessmentService scaledService = new AssessmentService(
                repository, progress, new AssessmentScoreEngine(), new LearnUnitPassPolicy(),
                (question, submittedCode) -> new CodingEvaluationResult(45, 18, 18,
                        "末尾多了感叹号。", List.of("移除末尾的感叹号")), planner);
        Assessment assessment = new Assessment(
                "assessment", "journey", "learnUnit-a", AssessmentType.LEARN_UNIT,
                AssessmentStatus.IN_PROGRESS, Instant.EPOCH, null);
        AssessmentAttempt openAttempt = attempt("attempt-1");
        LearnUnit learnUnit = new LearnUnit(
                "learnUnit-a", "typescript", "learnUnit-a", "chapter", "LearnUnit A", "description", 1, List.of(),
                80, 70, true, List.of("objective"), "intro", List.of("concept"), List.of("example"), true);
        Question coding = new Question(
                "coding", "learnUnit-a", QuestionType.CODING, 1, "Implement", 30,
                null, "{\"correctness\":60,\"languageUsage\":20,\"clarity\":20}",
                "typescript", "", "[]", false);
        when(repository.findAssessment("assessment")).thenReturn(Optional.of(assessment));
        when(repository.findOpenAttempt("assessment")).thenReturn(Optional.of(openAttempt));
        when(repository.listQuestionsForAssessment("assessment")).thenReturn(List.of(coding));
        when(repository.listQuestionAttempts("attempt-1")).thenReturn(List.of(
                new QuestionAttempt("coding", "attempt-1", "{}", null, 30, null, null, "answer", null, null)));
        when(repository.findLearnUnit("learnUnit-a")).thenReturn(Optional.of(learnUnit));
        when(repository.findAttempt("attempt-1")).thenReturn(Optional.of(new AssessmentAttempt(
                "attempt-1", "assessment", "journey", "learnUnit-a", 1,
                0, 24, 80, true, Instant.EPOCH, Instant.now())));

        AssessmentService.AssessmentSubmission result = scaledService.submit("assessment");

        assertTrue(result.passed());
        assertEquals(80, result.score().codingScore());
        assertEquals(80, result.score().totalScore());
        verify(repository).saveQuestionAttempt(argThat(value -> value.score() == 24
                && value.feedback().equals("末尾多了感叹号。")
                && value.evaluationJson().contains("移除末尾的感叹号")));
        verify(repository).updateAttempt(eq("attempt-1"), eq(0), eq(80), eq(80), eq(true), any(Instant.class));
    }

    @Test
    void learnUnitAssessmentCanUseCodingOnlyQuestions() {
        LearningJourney journey = new LearningJourney(
                "journey", "user", "typescript", "learn coding", com.example.agent.learning.journey.JourneyStatus.ACTIVE,
                Instant.EPOCH, Instant.EPOCH);
        LearningLanguage language = new LearningLanguage(
                "language", "typescript", "TypeScript", "typed JavaScript", true);
        LearnUnit learnUnit = new LearnUnit(
                "learnUnit-a", "typescript", "learnUnit-a", "chapter", "LearnUnit A", "description", 1, List.of(),
                80, 70, true, List.of("objective"), "intro", List.of("concept"), List.of("example"), false);
        Question coding = new Question(
                "coding-only", learnUnit.code(), QuestionType.CODING, 1, "Implement", 100,
                null, "{\"correctness\":60,\"languageUsage\":20,\"clarity\":20}",
                "typescript", "", "[]", false);
        when(repository.findJourney("journey")).thenReturn(Optional.of(journey));
        when(repository.findLearnUnit("learnUnit-a")).thenReturn(Optional.of(learnUnit));
        when(repository.listLearnUnitsForJourney("journey")).thenReturn(List.of(learnUnit));
        when(repository.findLatestLearnUnitAssessment("journey", "learnUnit-a")).thenReturn(Optional.empty());
        when(repository.findLanguage("typescript")).thenReturn(Optional.of(language));
        when(repository.findProfile("journey")).thenReturn(Optional.empty());
        when(repository.listQuestionsForLearnUnit("learnUnit-a")).thenReturn(List.of(coding));
        when(repository.findOpenAttempt(anyString())).thenReturn(Optional.empty());
        when(repository.listQuestionsForAssessment(anyString())).thenReturn(List.of(coding));
        when(repository.listAttemptsForAssessment(anyString())).thenReturn(List.of());

        AssessmentService.AssessmentState state = service.createLearnUnitAssessment("journey", "learnUnit-a");

        assertEquals(List.of(coding), state.questions());
        verify(repository, never()).insertGeneratedQuestion(any(Question.class));
        verify(repository).insertAssessment(any(Assessment.class));
    }

    @Test
    void retryKeepsAssessmentQuestionSetAndHistory() {
        Assessment assessment = new Assessment(
                "assessment", "journey", "learnUnit-a", AssessmentType.LEARN_UNIT,
                AssessmentStatus.COMPLETED, Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
        AssessmentAttempt previous = new AssessmentAttempt(
                "attempt-1", assessment.id(), assessment.journeyId(), assessment.learnUnitCode(), 1,
                80, null, 80, true, Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
        Question choice = new Question(
                "choice", "learnUnit-a", QuestionType.MULTIPLE_CHOICE, 1, "Choose", 20,
                "{\"correctOptionIds\":[\"A\"]}", null, null, null, "[]", false);
        when(repository.findAssessment("assessment")).thenReturn(
                Optional.of(assessment), Optional.of(new Assessment(
                        assessment.id(), assessment.journeyId(), assessment.learnUnitCode(), assessment.type(),
                        AssessmentStatus.IN_PROGRESS, assessment.createdAt(), assessment.completedAt())));
        when(repository.findOpenAttempt("assessment")).thenReturn(Optional.empty());
        when(repository.nextAttemptNumber("assessment")).thenReturn(2);
        when(repository.listQuestionsForAssessment("assessment")).thenReturn(List.of(choice));
        when(repository.listAttemptsForAssessment("assessment")).thenReturn(List.of(previous));

        AssessmentService.AssessmentState state = service.start("assessment");

        assertEquals(List.of(choice), state.questions());
        assertEquals(List.of(previous), state.attempts());
        verify(repository).insertAttempt(argThat(value -> value.assessmentId().equals("assessment")
                && value.attemptNumber() == 2 && value.completedAt() == null));
        verify(repository, never()).insertAssessmentQuestion(anyString(), anyString(), any(Integer.class));
    }

    @Test
    void codingEvaluationFailureKeepsDraftAndReturnsLearnUnitToLearning() {
        AssessmentService failingService = new AssessmentService(
                repository, progress, new AssessmentScoreEngine(), new LearnUnitPassPolicy(),
                (question, submittedCode) -> {
                    throw new IllegalStateException("provider unavailable");
                }, planner);
        Assessment assessment = new Assessment(
                "assessment", "journey", "learnUnit-a", AssessmentType.LEARN_UNIT,
                AssessmentStatus.IN_PROGRESS, Instant.EPOCH, null);
        AssessmentAttempt openAttempt = attempt("attempt-1");
        Question coding = new Question(
                "coding", "learnUnit-a", QuestionType.CODING, 1, "Implement", 100,
                null, "{\"correctness\":60}", "typescript", "", "[]", false);
        when(repository.findAssessment("assessment")).thenReturn(Optional.of(assessment));
        when(repository.findOpenAttempt("assessment")).thenReturn(Optional.of(openAttempt));
        when(repository.listQuestionsForAssessment("assessment")).thenReturn(List.of(coding));
        when(repository.listQuestionAttempts("attempt-1")).thenReturn(List.of());

        assertThrows(AssessmentService.AssessmentEvaluationException.class,
                () -> failingService.submit("assessment"));

        verify(progress).markAssessmentFailed("journey", "learnUnit-a");
        verify(repository, atLeastOnce()).saveQuestionAttempt(any(QuestionAttempt.class));
        verify(repository, never()).updateAttempt(anyString(), any(), any(), any(), any(), any(Instant.class));
        verify(repository, never()).updateAssessment(anyString(), any(), any(Instant.class));
    }

    @Test
    void learnUnitAssessmentGeneratesQuestionsWhenDatabaseHasNone() {
        LearningJourney journey = new LearningJourney(
                "journey", "user", "typescript", "learn", com.example.agent.learning.journey.JourneyStatus.ACTIVE,
                Instant.EPOCH, Instant.EPOCH);
        LearningLanguage language = new LearningLanguage(
                "language", "typescript", "TypeScript", "typed JavaScript", true);
        LearnUnit learnUnit = new LearnUnit(
                "learnUnit-a", "typescript", "learnUnit-a", "chapter", "LearnUnit A", "description", 1, List.of(),
                80, 70, true, List.of("objective"), "intro", List.of("concept"), List.of("example"), false);
        Question choice = new Question(
                "generated-choice", "learnUnit-a", QuestionType.MULTIPLE_CHOICE, 1, "Choose", 20,
                "{\"options\":[{\"id\":\"A\",\"text\":\"yes\"},{\"id\":\"B\",\"text\":\"no\"}],"
                        + "\"correctOptionIds\":[\"A\"],\"multiple\":false}", null, null, null, "[]", true);
        Question coding = new Question(
                "generated-coding", "learnUnit-a", QuestionType.CODING, 2, "Implement", 100,
                null, "{\"correctness\":60,\"languageUsage\":20,\"clarity\":20}",
                "typescript", "", "[]", true);
        when(repository.findJourney("journey")).thenReturn(Optional.of(journey));
        when(repository.findLearnUnit("learnUnit-a")).thenReturn(Optional.of(learnUnit));
        when(repository.listLearnUnitsForJourney("journey")).thenReturn(List.of(learnUnit));
        when(repository.findLatestLearnUnitAssessment("journey", "learnUnit-a")).thenReturn(Optional.empty());
        when(repository.findLanguage("typescript")).thenReturn(Optional.of(language));
        when(repository.findProfile("journey")).thenReturn(Optional.empty());
        when(repository.listQuestionsForLearnUnit("learnUnit-a")).thenReturn(List.of());
        when(planner.plan(any(), any(), any(), any())).thenReturn(List.of(choice, coding));
        when(repository.findOpenAttempt(anyString())).thenReturn(Optional.empty());
        when(repository.listQuestionsForAssessment(anyString())).thenReturn(List.of(choice, coding));
        when(repository.listAttemptsForAssessment(anyString())).thenReturn(List.of());

        AssessmentService.AssessmentState state = service.createLearnUnitAssessment("journey", "learnUnit-a");

        assertEquals("journey", state.assessment().journeyId());
        assertEquals("learnUnit-a", state.assessment().learnUnitCode());
        assertEquals(AssessmentType.LEARN_UNIT, state.assessment().type());
        assertEquals(AssessmentStatus.CREATED, state.assessment().status());
        assertEquals(List.of(choice, coding), state.questions());
        verify(repository).insertGeneratedQuestion(choice);
        verify(repository).insertGeneratedQuestion(coding);
        verify(repository).insertAssessment(any(Assessment.class));
        verify(repository).insertAssessmentQuestion(anyString(), eq("generated-choice"), eq(0));
        verify(repository).insertAssessmentQuestion(anyString(), eq("generated-coding"), eq(1));
    }

    @Test
    void choiceOnlyLearnUnitDoesNotRequireCodingQuestion() {
        LearningJourney journey = new LearningJourney(
                "journey", "user", "reading", "learn docs", com.example.agent.learning.journey.JourneyStatus.ACTIVE,
                Instant.EPOCH, Instant.EPOCH);
        LearningLanguage language = new LearningLanguage(
                "language", "reading", "Reading", "reading path", true);
        LearnUnit learnUnit = new LearnUnit(
                "learnUnit-reading", "reading", "learnUnit-reading", "chapter", "Read docs", "documentation",
                1, List.of(), 80, null, true, List.of("Read docs"), "Read", List.of("terms"), List.of("API"), false);
        Question choice = new Question(
                "generated-choice-only", learnUnit.code(), QuestionType.MULTIPLE_CHOICE, 1, "Choose", 20,
                "{\"options\":[{\"id\":\"A\",\"text\":\"yes\"},{\"id\":\"B\",\"text\":\"no\"}],"
                        + "\"correctOptionIds\":[\"A\"],\"multiple\":false}",
                null, null, null, "[]", true);
        when(repository.findJourney("journey")).thenReturn(Optional.of(journey));
        when(repository.findLearnUnit("learnUnit-reading")).thenReturn(Optional.of(learnUnit));
        when(repository.listLearnUnitsForJourney("journey")).thenReturn(List.of(learnUnit));
        when(repository.findLatestLearnUnitAssessment("journey", "learnUnit-reading")).thenReturn(Optional.empty());
        when(repository.findLanguage("reading")).thenReturn(Optional.of(language));
        when(repository.findProfile("journey")).thenReturn(Optional.empty());
        when(repository.listQuestionsForLearnUnit("learnUnit-reading")).thenReturn(List.of());
        when(planner.plan(any(), any(), any(), any())).thenReturn(List.of(choice));
        when(repository.findOpenAttempt(anyString())).thenReturn(Optional.empty());
        when(repository.listQuestionsForAssessment(anyString())).thenReturn(List.of(choice));
        when(repository.listAttemptsForAssessment(anyString())).thenReturn(List.of());

        AssessmentService.AssessmentState state = service.createLearnUnitAssessment("journey", "learnUnit-reading");

        assertEquals(List.of(choice), state.questions());
        verify(repository).insertGeneratedQuestion(choice);
        verify(repository).insertAssessmentQuestion(anyString(), eq(choice.id()), eq(0));
    }

    @Test
    void diagnosticRejectsIncompleteEvidenceBeforePersistence() {
        LearningJourney journey = new LearningJourney(
                "journey", "user", "reading", "learn docs", com.example.agent.learning.journey.JourneyStatus.ACTIVE,
                Instant.EPOCH, Instant.EPOCH);
        LearningLanguage language = new LearningLanguage(
                "language", "reading", "Reading", "reading path", true);
        LearnUnit learnUnit = new LearnUnit(
                "learnUnit-reading", "reading", "learnUnit-reading", "chapter", "Read docs", "documentation",
                1, List.of(), 80, null, true, List.of("Read docs"), "Read", List.of("terms"), List.of("API"), true);
        Question choice = new Question(
                "diagnostic-choice", learnUnit.code(), QuestionType.MULTIPLE_CHOICE, 1, "Choose", 20,
                "{\"options\":[{\"id\":\"A\",\"text\":\"yes\"},{\"id\":\"B\",\"text\":\"no\"}],"
                        + "\"correctOptionIds\":[\"A\"],\"multiple\":false}",
                null, null, null, "[]", true);
        when(repository.findJourney("journey")).thenReturn(Optional.of(journey));
        when(repository.findLanguage("reading")).thenReturn(Optional.of(language));
        when(repository.listLearnUnitsForJourney("journey")).thenReturn(List.of(learnUnit));
        when(repository.listDiagnosticQuestionsForJourney("journey")).thenReturn(List.of(choice));
        when(repository.findProfile("journey")).thenReturn(Optional.empty());
        when(planner.plan(any(), any(), any(), any())).thenReturn(List.of(choice));

        assertThrows(IllegalStateException.class, () -> service.createDiagnostic("journey"));

        verify(planner).plan(any(), any(), any(), any());
        verify(repository, never()).insertGeneratedQuestion(any(Question.class));
        verify(repository, never()).insertAssessment(any(Assessment.class));
    }

    @Test
    void diagnosticUsesPlannerToCompleteExistingCatalogAndKeepsQuestionSetFixed() {
        LearningJourney journey = new LearningJourney(
                "journey", "user", "reading", "learn docs", com.example.agent.learning.journey.JourneyStatus.ACTIVE,
                Instant.EPOCH, Instant.EPOCH);
        LearningLanguage language = new LearningLanguage(
                "language", "reading", "Reading", "reading path", true);
        LearnUnit learnUnit = new LearnUnit(
                "learnUnit-reading", "reading", "learnUnit-reading", "chapter", "Read docs", "documentation",
                1, List.of(), 80, null, true, List.of("Read docs"), "Read", List.of("terms"), List.of("API"), true);
        Question first = new Question(
                "diagnostic-choice-1", learnUnit.code(), QuestionType.MULTIPLE_CHOICE, 1, "Choose one", 20,
                "{\"options\":[{\"id\":\"A\",\"text\":\"yes\"},{\"id\":\"B\",\"text\":\"no\"}],"
                        + "\"correctOptionIds\":[\"A\"],\"multiple\":false}",
                null, null, null, "[]", true);
        Question second = new Question(
                "diagnostic-choice-2", learnUnit.code(), QuestionType.MULTIPLE_CHOICE, 2, "Choose two", 20,
                "{\"options\":[{\"id\":\"A\",\"text\":\"yes\"},{\"id\":\"B\",\"text\":\"no\"}],"
                        + "\"correctOptionIds\":[\"B\"],\"multiple\":false}",
                null, null, null, "[]", true);
        when(repository.findDiagnosticAssessment("journey")).thenReturn(Optional.empty());
        when(repository.findJourney("journey")).thenReturn(Optional.of(journey));
        when(repository.findLanguage("reading")).thenReturn(Optional.of(language));
        when(repository.listLearnUnitsForJourney("journey")).thenReturn(List.of(learnUnit));
        when(repository.listDiagnosticQuestionsForJourney("journey")).thenReturn(List.of(first));
        when(repository.findProfile("journey")).thenReturn(Optional.empty());
        when(planner.plan(any(), any(), any(), any())).thenReturn(List.of(first, second));
        when(repository.findOpenAttempt(anyString())).thenReturn(Optional.empty());
        when(repository.listQuestionsForAssessment(anyString())).thenReturn(List.of(first, second));
        when(repository.listAttemptsForAssessment(anyString())).thenReturn(List.of());

        AssessmentService.AssessmentState state = service.createDiagnostic("journey");

        assertEquals(List.of(first, second), state.questions());
        verify(planner).plan(any(), any(), any(), any());
        verify(repository).insertGeneratedQuestion(second);
        verify(repository).insertAssessment(any(Assessment.class));
        verify(repository).insertAssessmentQuestion(anyString(), eq(first.id()), eq(0));
        verify(repository).insertAssessmentQuestion(anyString(), eq(second.id()), eq(1));

        when(repository.findDiagnosticAssessment("journey")).thenReturn(Optional.of(state.assessment()));
        AssessmentService.AssessmentState resumed = service.createDiagnostic("journey");

        assertEquals(state.assessment(), resumed.assessment());
        verify(planner, times(1)).plan(any(), any(), any(), any());
        verify(repository, times(1)).insertAssessment(any(Assessment.class));
    }

    @Test
    void completingDiagnosticScoresInJavaAndUpdatesLearningPath() {
        Assessment assessment = new Assessment(
                "diagnostic", "journey", null, AssessmentType.DIAGNOSTIC,
                AssessmentStatus.IN_PROGRESS, Instant.EPOCH, null);
        AssessmentAttempt openAttempt = new AssessmentAttempt(
                "attempt", assessment.id(), "journey", null, 1,
                null, null, null, null, Instant.EPOCH, null);
        Question first = new Question(
                "diagnostic-choice-1", "learnUnit-a", QuestionType.MULTIPLE_CHOICE, 1, "Choose one", 20,
                "{\"options\":[{\"id\":\"A\",\"text\":\"yes\"},{\"id\":\"B\",\"text\":\"no\"}],"
                        + "\"correctOptionIds\":[\"A\"],\"multiple\":false}",
                null, null, null, "[]", true);
        Question second = new Question(
                "diagnostic-choice-2", "learnUnit-a", QuestionType.MULTIPLE_CHOICE, 2, "Choose two", 20,
                "{\"options\":[{\"id\":\"A\",\"text\":\"yes\"},{\"id\":\"B\",\"text\":\"no\"}],"
                        + "\"correctOptionIds\":[\"B\"],\"multiple\":false}",
                null, null, null, "[]", true);
        when(repository.findAssessment(assessment.id())).thenReturn(Optional.of(assessment));
        when(repository.findOpenAttempt(assessment.id())).thenReturn(Optional.of(openAttempt));
        when(repository.listQuestionsForAssessment(assessment.id())).thenReturn(List.of(first, second));
        when(repository.listQuestionAttempts(openAttempt.id())).thenReturn(List.of(
                new QuestionAttempt(first.id(), openAttempt.id(), "{}", 20, 20, "Correct.", true, null, null, "[\"A\"]"),
                new QuestionAttempt(second.id(), openAttempt.id(), "{}", 20, 20, "Correct.", true, null, null, "[\"B\"]")));
        when(repository.findAttempt(openAttempt.id())).thenReturn(Optional.of(new AssessmentAttempt(
                openAttempt.id(), assessment.id(), "journey", null, 1,
                100, null, 100, true, Instant.EPOCH, Instant.now())));

        AssessmentService.AssessmentSubmission result = service.submit(assessment.id());

        assertTrue(result.passed());
        assertEquals(100, result.score().totalScore());
        assertEquals(1, result.learnUnitResults().size());
        verify(progress).recordDiagnosticResult(
                "journey", "learnUnit-a", new AssessmentScore(100, 0, 100, true, false), true);
        verify(progress).generatePath("journey");
        verify(repository).updateAssessment(eq(assessment.id()), eq(AssessmentStatus.COMPLETED), any(Instant.class));
    }

    private AssessmentAttempt attempt(String id) {
        return new AssessmentAttempt(id, "assessment", "journey", "learnUnit-a", id.endsWith("1") ? 1 : 2,
                null, null, null, null, Instant.EPOCH, null);
    }
}
