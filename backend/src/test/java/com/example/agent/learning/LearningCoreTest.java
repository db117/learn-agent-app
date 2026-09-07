package com.example.agent.learning;

import com.example.agent.learning.assessment.QuestionType;
import com.example.agent.learning.catalog.LearningSkill;
import com.example.agent.learning.journey.LearnerSkill;
import com.example.agent.learning.journey.LearnerSkillStatus;
import com.example.agent.learning.path.DeterministicLearningPathPlanner;
import com.example.agent.learning.path.LearningPathItemStatus;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.scoring.AssessmentScoreEngine;
import com.example.agent.learning.scoring.SkillPassPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LearningCoreTest {

    private final AssessmentScoreEngine scoreEngine = new AssessmentScoreEngine();
    private final SkillPassPolicy passPolicy = new SkillPassPolicy();

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
        LearningSkill skill = skill("skill-a", 1, 80, List.of());
        assertFalse(passPolicy.passed(new AssessmentScore(100, 0, 79, true, false), skill));
        assertTrue(passPolicy.passed(new AssessmentScore(100, 0, 80, true, false), skill));
        assertFalse(passPolicy.passed(new AssessmentScore(100, 69, 82, true, true), skill));
        assertTrue(passPolicy.passed(new AssessmentScore(100, 70, 80, true, true), skill));
        assertFalse(passPolicy.diagnosticPassed(new AssessmentScore(100, 100, 85, true, true), 1));
        assertTrue(passPolicy.diagnosticPassed(new AssessmentScore(100, 100, 85, true, true), 2));
        assertFalse(passPolicy.diagnosticPassed(new AssessmentScore(100, 100, 84, true, true), 2));
    }

    @Test
    void pathPlannerOrdersPrerequisitesAndKeepsPassedSkillsCompleted() {
        LearningSkill advanced = skill("skill-b", 2, 80, List.of("skill-a"));
        LearningSkill basics = skill("skill-a", 1, 80, List.of());
        LearnerSkill passedBasics = new LearnerSkill(
                "journey", "skill-a", LearnerSkillStatus.PASSED, 90, 90, 1, null, null, null, null);

        var path = new DeterministicLearningPathPlanner().plan(
                "journey", List.of(advanced, basics), Map.of("skill-a", passedBasics));

        assertEquals(List.of("skill-a", "skill-b"), path.stream().map(item -> item.skillCode()).toList());
        assertEquals(LearningPathItemStatus.COMPLETED, path.get(0).status());
        assertEquals(LearningPathItemStatus.CURRENT, path.get(1).status());
    }

    private LearningSkill skill(String code, int sequence, int passScore, List<String> prerequisites) {
        return new LearningSkill(
                code, "typescript", code, code, "description", sequence, prerequisites, passScore, 70,
                true, List.of("objective"), "intro", List.of("concept"), List.of("example"), true);
    }
}
