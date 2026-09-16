package com.db117.learnagent.learning.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.time.Instant;

/**
 * Learner 保存当前用户的背景能力，不承载 Journey 的学习进度。
 *
 * @param id SQLite 自增主键；对象首次创建时为 {@code null}
 * @param displayName 学习者在产品中显示的名称
 * @param backgroundSummary 学习者自己确认的背景能力描述，作为 Tutor 的唯一背景事实源
 * @param createdAt 学习者身份创建时间，由调用方提供
 */
public record Learner(Long id, String displayName, String backgroundSummary, Instant createdAt) {
    public Learner {
        if (id != null && id <= 0) {
            throw new DomainRuleViolation("id must be positive");
        }
        displayName = DomainChecks.text(displayName, "displayName");
        backgroundSummary = DomainChecks.text(backgroundSummary, "backgroundSummary");
        createdAt = DomainChecks.time(createdAt, "createdAt");
    }

    public static Learner create(String displayName, String backgroundSummary, Instant createdAt) {
        return new Learner(null, displayName, backgroundSummary, createdAt);
    }

    public Learner withId(long persistedId) {
        return new Learner(DomainChecks.id(persistedId, "id"), displayName, backgroundSummary, createdAt);
    }

    public Learner withBackgroundSummary(String nextBackgroundSummary) {
        return new Learner(id, displayName, nextBackgroundSummary, createdAt);
    }
}
