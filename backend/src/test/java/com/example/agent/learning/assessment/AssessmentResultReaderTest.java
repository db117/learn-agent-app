package com.example.agent.learning.assessment;

import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.scoring.AssessmentScoreEngine;
import com.example.agent.learning.scoring.LearnUnitPassPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssessmentResultReaderTest {

    private final LearningRepository repository = mock(LearningRepository.class);
    private final AssessmentScoreEngine scoreEngine = new AssessmentScoreEngine();
    private final LearnUnitPassPolicy passPolicy = new LearnUnitPassPolicy();
    private final AssessmentResultReader reader = new AssessmentResultReader(
            new AssessmentWorkflowSupport(repository, scoreEngine, passPolicy,
                    (question, submittedCode) -> new CodingEvaluationResult(0, 0, 0, "", List.of())),
            repository, scoreEngine, passPolicy);

    @Test
    void completedResultReadsAllThreeAssessmentTypesWithoutSubmittingAgain() {
        Assessment diagnostic = new Assessment(
                "diagnostic", "journey", null, AssessmentType.DIAGNOSTIC,
                AssessmentStatus.COMPLETED, Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
        Assessment learnUnit = new Assessment(
                "learn-unit", "journey", "unit", AssessmentType.LEARN_UNIT,
                AssessmentStatus.COMPLETED, Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
        Assessment synthesis = new Assessment(
                "synthesis", "journey", null, "chapter", AssessmentType.CHAPTER_SYNTHESIS,
                AssessmentStatus.COMPLETED, Instant.EPOCH, Instant.EPOCH.plusSeconds(1));
        Question diagnosticA = choice("diagnostic-a", "unit", true, QuestionRole.DIAGNOSTIC);
        Question diagnosticB = choice("diagnostic-b", "unit", true, QuestionRole.DIAGNOSTIC);
        Question independent = choice("independent", "unit", false, QuestionRole.INDEPENDENT);
        Question synthesisQuestion = new Question(
                "synthesis-question", null, "chapter", QuestionType.MULTIPLE_CHOICE, 1,
                "Synthesize", 20, config(), null, null, null, "[\"unit\"]", false, QuestionRole.SYNTHESIS);
        stubCompleted(diagnostic, List.of(diagnosticA, diagnosticB), List.of(
                correctAttempt(diagnosticA, "attempt-diagnostic"), correctAttempt(diagnosticB, "attempt-diagnostic")));
        stubCompleted(learnUnit, List.of(independent), List.of(correctAttempt(independent, "attempt-learn-unit")));
        stubCompleted(synthesis, List.of(synthesisQuestion), List.of(correctAttempt(synthesisQuestion, "attempt-synthesis")));
        when(repository.findLearnUnit("unit")).thenReturn(Optional.of(learnUnit()));
        when(repository.listLearnUnitsForJourney("journey")).thenReturn(List.of());
        when(repository.listPath("journey")).thenReturn(List.of());

        AssessmentService.AssessmentSubmission diagnosticResult = reader.completedResult("diagnostic");
        AssessmentService.AssessmentSubmission learnUnitResult = reader.completedResult("learn-unit");
        AssessmentService.AssessmentSubmission synthesisResult = reader.completedResult("synthesis");

        assertTrue(diagnosticResult.passed());
        assertEquals(1, diagnosticResult.learnUnitResults().size());
        assertEquals(85, diagnosticResult.passScore());
        assertTrue(learnUnitResult.passed());
        assertEquals(80, learnUnitResult.passScore());
        assertFalse(learnUnitResult.score().hasCodingQuestions());
        assertTrue(synthesisResult.passed());
        assertEquals(80, synthesisResult.passScore());
        assertTrue(synthesisResult.chapterCompleted());
    }

    private void stubCompleted(
            Assessment assessment, List<Question> questions, List<QuestionAttempt> questionAttempts) {
        AssessmentAttempt attempt = new AssessmentAttempt(
                "attempt-" + assessment.id(), assessment.id(), assessment.journeyId(), assessment.learnUnitCode(),
                1, 100, null, 100, true, Instant.EPOCH, assessment.completedAt());
        when(repository.findAssessment(assessment.id())).thenReturn(Optional.of(assessment));
        when(repository.listAttemptsForAssessment(assessment.id())).thenReturn(List.of(attempt));
        when(repository.listQuestionsForAssessment(assessment.id())).thenReturn(questions);
        when(repository.listQuestionAttempts(attempt.id())).thenReturn(questionAttempts);
    }

    private QuestionAttempt correctAttempt(Question question, String attemptId) {
        return new QuestionAttempt(
                question.id(), attemptId, "{}", question.points(), question.points(), "Correct.", true,
                null, null, "[\"A\"]");
    }

    private Question choice(String id, String learnUnitCode, boolean diagnostic, QuestionRole role) {
        return new Question(
                id, learnUnitCode, QuestionType.MULTIPLE_CHOICE, 1, "Choose", 20,
                config(), null, null, null, "[]", diagnostic, role);
    }

    private String config() {
        return "{\"options\":[{\"id\":\"A\",\"text\":\"yes\"},{\"id\":\"B\",\"text\":\"no\"}],"
                + "\"correctOptionIds\":[\"A\"],\"multiple\":false}";
    }

    private LearnUnit learnUnit() {
        return new LearnUnit(
                "unit", "typescript", "unit", "chapter", "Unit", "description", 1, List.of(),
                80, null, true, List.of("objective"), "intro", List.of("concept"), List.of("example"), true);
    }
}
