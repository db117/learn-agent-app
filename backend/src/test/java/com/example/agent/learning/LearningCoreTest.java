package com.example.agent.learning;

import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.assessment.Question;
import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.catalog.LearningLanguage;
import com.example.agent.learning.diagnostic.DeterministicDiagnosticQuestionPlanner;
import com.example.agent.learning.journey.LearnerProfile;
import com.example.agent.learning.journey.LearnerLearnUnit;
import com.example.agent.learning.journey.LearnerLearnUnitStatus;
import com.example.agent.learning.path.DeterministicLearningPathPlanner;
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
    void scoreRejectsOutOfRangeQuestionScores() {
        assertThrows(IllegalArgumentException.class, () -> scoreEngine.score(List.of(
                new AssessmentScoreEngine.ScoredQuestion(QuestionType.CODING, 101, 100))));
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
        LearnerLearnUnit passedBasics = new LearnerLearnUnit(
                "journey", "learnUnit-a", LearnerLearnUnitStatus.PASSED, 90, 90, 1, null, null, null, null);

        var path = new DeterministicLearningPathPlanner().plan(
                "journey", List.of(advanced, basics), Map.of("learnUnit-a", passedBasics));

        assertEquals(List.of("learnUnit-a", "learnUnit-b"), path.stream().map(item -> item.learnUnitCode()).toList());
        assertEquals(LearningPathItemStatus.COMPLETED, path.get(0).status());
        assertEquals(LearningPathItemStatus.CURRENT, path.get(1).status());
    }

    @Test
    void deterministicQuestionFallbackAlsoSupportsNonDiagnosticLearnUnits() {
        LearnUnit learnUnit = learnUnit("learnUnit-a", 1, 80, List.of());
        Question choice = new Question(
                "choice", learnUnit.code(), QuestionType.MULTIPLE_CHOICE, 1, "Choose", 20,
                "{\"correctOptionIds\":[\"A\"]}", null, null, null, "[]", false);
        Question coding = new Question(
                "coding", learnUnit.code(), QuestionType.CODING, 1, "Implement", 100,
                null, "{\"correctness\":60}", "typescript", "", "[]", false);

        assertEquals(List.of(choice, coding), new DeterministicDiagnosticQuestionPlanner().plan(
                new LearningLanguage("language", "typescript", "TypeScript", "typed JavaScript", true),
                List.of(learnUnit), List.of(choice, coding),
                new LearnerProfile("journey", "java", 8, "backend developer", "learn TypeScript")));
    }

    private LearnUnit learnUnit(String code, int sequence, int passScore, List<String> prerequisites) {
        return new LearnUnit(
                code, "typescript", code, code, "description", sequence, prerequisites, passScore, 70,
                true, List.of("objective"), "intro", List.of("concept"), List.of("example"), false);
    }
}
