package com.example.agent.learning;

import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.assessment.QuestionAttempt;
import com.example.agent.learning.assessment.QuestionStructureValidator;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.path.DeterministicLearningPathPlanner;
import com.example.agent.learning.path.LearningPathItem;
import com.example.agent.learning.path.LearningPathItemStatus;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.scoring.AssessmentScoreEngine;
import com.example.agent.learning.scoring.LearnUnitPassPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningCoreTest {

    private final AssessmentScoreEngine scoreEngine = new AssessmentScoreEngine();
    private final LearnUnitPassPolicy passPolicy = new LearnUnitPassPolicy();

    @Test
    void scoreUsesConfiguredWeightsAndRenormalizesWhenATypeIsMissing() {
        AssessmentScore mixed = scoreEngine.score(List.of(
                new AssessmentScoreEngine.ScoredQuestion(QuestionType.MULTIPLE_CHOICE, 20, 20),
                new AssessmentScoreEngine.ScoredQuestion(QuestionType.CODING, 80, 100)));

        assertEquals(100, mixed.choiceScore());
        assertEquals(80, mixed.codingScore());
        assertEquals(88, mixed.totalScore());

        AssessmentScore choiceOnly = scoreEngine.score(List.of(
                new AssessmentScoreEngine.ScoredQuestion(QuestionType.MULTIPLE_CHOICE, 10, 20)));
        assertEquals(50, choiceOnly.totalScore());
        assertFalse(choiceOnly.hasCodingQuestions());
    }

    @Test
    void codingOnlyAssessmentUsesCodingPercentageAsTotal() {
        AssessmentScore score = scoreEngine.score(List.of(
                new AssessmentScoreEngine.ScoredQuestion(QuestionType.CODING, 70, 100)));

        assertEquals(70, score.codingScore());
        assertEquals(70, score.totalScore());
        assertFalse(score.hasChoiceQuestions());
        assertTrue(score.hasCodingQuestions());
        assertTrue(passPolicy.passed(score, learnUnit("learnUnit-a", 1, 70, List.of())));
    }

    @Test
    void scoreRejectsOutOfRangeQuestionScores() {
        assertThrows(IllegalArgumentException.class, () -> scoreEngine.score(List.of(
                new AssessmentScoreEngine.ScoredQuestion(QuestionType.CODING, 101, 100))));
    }

    @Test
    void scoreAttemptsRejectsUnevaluatedQuestionsInsteadOfTreatingThemAsZero() {
        assertThrows(IllegalArgumentException.class, () -> scoreEngine.scoreAttempts(
                List.of(new QuestionAttempt(
                        "coding", "attempt", "{}", null, 100, null, null, "code", null, null)),
                Map.of("coding", QuestionType.CODING)));
    }

    @Test
    void codingRubricRequiresNumericWeights() {
        Question coding = new Question(
                "coding", "learnUnit-a", QuestionType.CODING, 1, "Implement", 100,
                null, "{\"correctness\":\"high\"}", "typescript", "", "[]", false);

        assertThrows(IllegalArgumentException.class, () -> QuestionStructureValidator.validate(coding));
    }

    @Test
    void passPolicyHonorsTotalCodingAndDiagnosticEvidenceBoundaries() {
        LearnUnit learnUnit = learnUnit("learnUnit-a", 1, 80, List.of());
        assertFalse(passPolicy.passed(new AssessmentScore(100, 0, 79, true, false), learnUnit));
        assertTrue(passPolicy.passed(new AssessmentScore(100, 0, 80, true, false), learnUnit));
        assertFalse(passPolicy.passed(new AssessmentScore(100, 69, 82, true, true), learnUnit));
        assertTrue(passPolicy.passed(new AssessmentScore(100, 70, 80, true, true), learnUnit));
        assertFalse(passPolicy.diagnosticPassed(new AssessmentScore(100, 100, 85, true, true), 1));
        assertTrue(passPolicy.diagnosticPassed(new AssessmentScore(100, 100, 85, true, true), 2));
        assertFalse(passPolicy.diagnosticPassed(new AssessmentScore(100, 100, 84, true, true), 2));
    }

    @Test
    void pathPlannerOrdersPrerequisitesAndKeepsPassedLearnUnitsCompleted() {
        LearnUnit advanced = learnUnit("learnUnit-b", 2, 80, List.of("learnUnit-a"));
        LearnUnit basics = learnUnit("learnUnit-a", 1, 80, List.of());
        LearningPathItem passedBasics = new LearningPathItem(
                "path-a", "journey", "learnUnit-a", 1, LearningPathItemStatus.COMPLETED);

        var path = new DeterministicLearningPathPlanner().plan(
                "journey", List.of(advanced, basics), List.of(passedBasics));

        assertEquals(List.of("learnUnit-a", "learnUnit-b"), path.stream().map(item -> item.learnUnitCode()).toList());
        assertEquals(LearningPathItemStatus.COMPLETED, path.get(0).status());
        assertEquals(LearningPathItemStatus.CURRENT, path.get(1).status());
    }

    private LearnUnit learnUnit(String code, int sequence, int passScore, List<String> prerequisites) {
        return new LearnUnit(
                code, "typescript", code, code, "description", sequence, prerequisites, passScore, 70,
                true, List.of("objective"), "intro", List.of("concept"), List.of("example"), false);
    }
}
