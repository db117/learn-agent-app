package com.db117.learnagent.practice.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.time.Instant;

/**
 * 一次 Practice 提交及其证据快照；重试产生新的 Attempt。
 *
 * @param id SQLite 自增主键；新提交保存前为 {@code null}
 * @param submittedAt 本次提交发生时间
 * @param evidence 本次提交产生的不可变客观证据
 */
public record PracticeAttempt(Long id, Instant submittedAt, PracticeEvidence evidence) {
    public PracticeAttempt {
        if (id != null && id <= 0) {
            throw new DomainRuleViolation("id must be positive");
        }
        submittedAt = DomainChecks.time(submittedAt, "submittedAt");
        if (evidence == null) {
            throw new DomainRuleViolation("evidence must not be null");
        }
    }

    public static PracticeAttempt submit(PracticeEvidence evidence, Instant submittedAt) {
        return new PracticeAttempt(null, submittedAt, evidence);
    }

    public PracticeAttempt withId(long persistedId) {
        return new PracticeAttempt(DomainChecks.id(persistedId, "id"), submittedAt, evidence);
    }
}
