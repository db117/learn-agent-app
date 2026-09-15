package com.db117.learnagent.learning.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

/**
 * Journey-specific 的掌握结果，数值来源必须是确定性领域证据。
 *
 * @param score 当前 Journey 内该 LearnUnit 的最佳评估分数（0-100）
 * @param mastered 是否同时满足评估及格与 Practice 证据通过
 */
public record Mastery(int score, boolean mastered) {
    public Mastery {
        DomainChecks.score(score);
        if (mastered && score < 70) {
            throw new DomainRuleViolation("mastered requires a passing score");
        }
    }
}
