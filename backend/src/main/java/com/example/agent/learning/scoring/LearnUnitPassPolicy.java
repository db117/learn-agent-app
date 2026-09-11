package com.example.agent.learning.scoring;

import com.example.agent.learning.catalog.LearnUnit;
import org.springframework.stereotype.Component;

/**
 * LearnUnit 和诊断评估的通过规则。
 *
 * <p>规则集中在此处，避免控制器、评分器或前端重复判断通过条件。
 */
@Component
public class LearnUnitPassPolicy {

    public static final int DIAGNOSTIC_PASS_SCORE = 85;
    public static final int SYNTHESIS_PASS_SCORE = 80;

    public boolean passed(AssessmentScore score, LearnUnit learnUnit) {
        return passed(score, learnUnit.passScore(), learnUnit.minCodingScore());
    }

    public boolean passed(AssessmentScore score, int passScore, Integer minCodingScore) {
        return score.totalScore() >= passScore
                && (!score.hasCodingQuestions() || minCodingScore == null || score.codingScore() >= minCodingScore);
    }

    public boolean diagnosticPassed(AssessmentScore score) {
        return diagnosticPassed(score, 2);
    }

    public boolean diagnosticPassed(AssessmentScore score, int evidenceCount) {
        return evidenceCount >= 2 && score.totalScore() >= DIAGNOSTIC_PASS_SCORE;
    }

    public boolean synthesisPassed(AssessmentScore score) {
        return score.totalScore() >= SYNTHESIS_PASS_SCORE;
    }
}
