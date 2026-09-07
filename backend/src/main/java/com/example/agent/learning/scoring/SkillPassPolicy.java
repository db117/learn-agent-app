package com.example.agent.learning.scoring;

import com.example.agent.learning.catalog.LearningSkill;
import org.springframework.stereotype.Component;

/**
 * 技能和诊断评估的通过规则。
 *
 * <p>规则集中在此处，避免控制器、评分器或前端重复判断通过条件。
 */
@Component
public class SkillPassPolicy {

    public static final int DIAGNOSTIC_PASS_SCORE = 85;

    public boolean passed(AssessmentScore score, LearningSkill skill) {
        return passed(score, skill.passScore(), skill.minCodingScore());
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
}
