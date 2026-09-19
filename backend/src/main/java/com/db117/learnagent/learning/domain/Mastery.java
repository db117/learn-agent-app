package com.db117.learnagent.learning.domain;

import com.db117.learnagent.shared.domain.DomainChecks;

/**
 * Journey-specific 的掌握结果，数值来源必须是确定性领域证据。
 *
 * @param score 当前 Journey 内该 LearnUnit 的掌握分数（0-100）；没有数值评估时可为 0
 * @param mastered 是否已完成当前 LearnUnit
 */
public record Mastery(int score, boolean mastered) {
    public Mastery {
        DomainChecks.score(score);
    }
}
