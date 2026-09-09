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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

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
                "learnUnit-a", "typescript", "learnUnit-a", "LearnUnit A", "description", 1, List.of(),
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
    }

    @Test
    void learnUnitAssessmentGeneratesQuestionsWhenDatabaseHasNone() {
        LearningJourney journey = new LearningJourney(
                "journey", "user", "typescript", "learn", com.example.agent.learning.journey.JourneyStatus.ACTIVE,
                Instant.EPOCH, Instant.EPOCH, null);
        LearningLanguage language = new LearningLanguage(
                "language", "typescript", "TypeScript", "typed JavaScript", true);
        LearnUnit learnUnit = new LearnUnit(
                "learnUnit-a", "typescript", "learnUnit-a", "LearnUnit A", "description", 1, List.of(),
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
                Instant.EPOCH, Instant.EPOCH, null);
        LearningLanguage language = new LearningLanguage(
                "language", "reading", "Reading", "reading path", true);
        LearnUnit learnUnit = new LearnUnit(
                "learnUnit-reading", "reading", "learnUnit-reading", "Read docs", "documentation",
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

    private AssessmentAttempt attempt(String id) {
        return new AssessmentAttempt(id, "assessment", "journey", "learnUnit-a", id.endsWith("1") ? 1 : 2,
                null, null, null, null, Instant.EPOCH, null);
    }
}
