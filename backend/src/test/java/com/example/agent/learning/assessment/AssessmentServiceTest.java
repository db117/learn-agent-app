package com.example.agent.learning.assessment;

import com.example.agent.learning.catalog.LearningSkill;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.diagnostic.DiagnosticQuestionPlanner;
import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.progress.ProgressService;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.scoring.AssessmentScoreEngine;
import com.example.agent.learning.scoring.SkillPassPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
            repository, progress, new AssessmentScoreEngine(), new SkillPassPolicy(),
            new FakeCodingAnswerEvaluator(), planner);

    @Test
    void failedSkillAssessmentCanRetryWithoutOverwritingTheFirstAttempt() {
        Assessment assessment = new Assessment(
                "assessment", "journey", "skill-a", AssessmentType.SKILL,
                AssessmentStatus.IN_PROGRESS, Instant.EPOCH, null);
        LearningSkill skill = new LearningSkill(
                "skill-a", "typescript", "skill-a", "Skill A", "description", 1, List.of(),
                80, 70, true, List.of("objective"), "intro", List.of("concept"), List.of("example"), true);
        Question choice = new Question(
                "choice", "skill-a", QuestionType.MULTIPLE_CHOICE, 1, "Choose", 20,
                "{\"correctOptionIds\":[\"A\"]}", null, null, null, "[]", false);
        Question coding = new Question(
                "coding", "skill-a", QuestionType.CODING, 1, "Implement", 100,
                null, "{\"correctness\":60,\"languageUsage\":20,\"clarity\":20}",
                "typescript", "", "[]", false);
        AssessmentAttempt first = attempt("attempt-1");
        AssessmentAttempt second = attempt("attempt-2");
        when(repository.findAssessment("assessment")).thenReturn(Optional.of(assessment));
        when(repository.findOpenAttempt("assessment")).thenReturn(Optional.of(first), Optional.of(second));
        when(repository.listQuestionsForAssessment("assessment")).thenReturn(List.of(choice, coding));
        when(repository.findSkill("skill-a")).thenReturn(Optional.of(skill));
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
        verify(progress).recordSkillAssessment(
                "journey", "skill-a", new AssessmentScore(0, 100, 60, true, true), false);
        verify(progress).recordSkillAssessment(
                "journey", "skill-a", new AssessmentScore(100, 100, 100, true, true), true);
    }

    @Test
    void skillAssessmentGeneratesQuestionsWhenDatabaseHasNone() {
        LearningJourney journey = new LearningJourney(
                "journey", "user", "typescript", "learn", com.example.agent.learning.journey.JourneyStatus.ACTIVE,
                Instant.EPOCH, Instant.EPOCH, null);
        LearningLanguage language = new LearningLanguage(
                "language", "typescript", "TypeScript", "typed JavaScript", true);
        LearningSkill skill = new LearningSkill(
                "skill-a", "typescript", "skill-a", "Skill A", "description", 1, List.of(),
                80, 70, true, List.of("objective"), "intro", List.of("concept"), List.of("example"), false);
        Question choice = new Question(
                "generated-choice", "skill-a", QuestionType.MULTIPLE_CHOICE, 1, "Choose", 20,
                "{\"correctOptionIds\":[\"A\"]}", null, null, null, "[]", true);
        Question coding = new Question(
                "generated-coding", "skill-a", QuestionType.CODING, 2, "Implement", 100,
                null, "{\"correctness\":60,\"languageUsage\":20,\"clarity\":20}",
                "typescript", "", "[]", true);
        when(repository.findJourney("journey")).thenReturn(Optional.of(journey));
        when(repository.findSkill("skill-a")).thenReturn(Optional.of(skill));
        when(repository.listSkillsForJourney("journey")).thenReturn(List.of(skill));
        when(repository.findLatestSkillAssessment("journey", "skill-a")).thenReturn(Optional.empty());
        when(repository.findLanguage("typescript")).thenReturn(Optional.of(language));
        when(repository.findProfile("journey")).thenReturn(Optional.empty());
        when(repository.listQuestionsForSkill("skill-a")).thenReturn(List.of());
        when(planner.plan(any(), any(), any(), any())).thenReturn(List.of(choice, coding));
        when(repository.findOpenAttempt(anyString())).thenReturn(Optional.empty());
        when(repository.listQuestionsForAssessment(anyString())).thenReturn(List.of(choice, coding));
        when(repository.listAttemptsForAssessment(anyString())).thenReturn(List.of());

        AssessmentService.AssessmentState state = service.createSkillAssessment("journey", "skill-a");

        assertEquals("journey", state.assessment().journeyId());
        assertEquals("skill-a", state.assessment().skillCode());
        assertEquals(AssessmentType.SKILL, state.assessment().type());
        assertEquals(AssessmentStatus.CREATED, state.assessment().status());
        assertEquals(List.of(choice, coding), state.questions());
        verify(repository).insertGeneratedQuestion(choice);
        verify(repository).insertGeneratedQuestion(coding);
        verify(repository).insertAssessment(any(Assessment.class));
        verify(repository).insertAssessmentQuestion(anyString(), eq("generated-choice"), eq(0));
        verify(repository).insertAssessmentQuestion(anyString(), eq("generated-coding"), eq(1));
    }

    private AssessmentAttempt attempt(String id) {
        return new AssessmentAttempt(id, "assessment", "journey", "skill-a", id.endsWith("1") ? 1 : 2,
                null, null, null, null, Instant.EPOCH, null);
    }
}
