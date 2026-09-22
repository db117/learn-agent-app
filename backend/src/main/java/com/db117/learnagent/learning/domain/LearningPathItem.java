package com.db117.learnagent.learning.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.time.Instant;

/**
 * Journey 对单个 LearnUnit 的唯一进度事实；不要在 Agent State 中复制这些字段。
 *
 * @param id SQLite 自增主键；初次创建路径项时为 {@code null}
 * @param learnUnitCode 当前路径项对应的 LearnUnit 编码
 * @param sequence 路径中的稳定排序值
 * @param status {@code PENDING/CURRENT/COMPLETED/SKIPPED} 生命周期状态
 * @param practiceVerified 是否存在满足 VerificationPolicy 的 PracticeEvidence
 * @param passReason 进入 {@code COMPLETED} 的领域原因；未完成时为空
 * @param startedAt 首次进入 {@code CURRENT} 的时间
 * @param completedAt 进入 {@code COMPLETED} 的时间；未完成时为空
 * @param updatedAt 本路径项最近一次状态或证据变化时间
 */
public record LearningPathItem(
        Long id,
        String learnUnitCode,
        int sequence,
        LearningPathItemStatus status,
        boolean practiceVerified,
        String passReason,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt) {

    public LearningPathItem {
        if (id != null && id <= 0) {
            throw new DomainRuleViolation("id must be positive");
        }
        learnUnitCode = DomainChecks.text(learnUnitCode, "learnUnitCode");
        if (sequence < 0) {
            throw new DomainRuleViolation("sequence must not be negative");
        }
        status = status == null ? throwRule("status must not be null") : status;
        if (passReason != null && passReason.isBlank()) {
            passReason = null;
        }
        if (startedAt != null && updatedAt != null && updatedAt.isBefore(startedAt)) {
            throw new DomainRuleViolation("updatedAt must not precede startedAt");
        }
        if (completedAt != null && updatedAt != null && updatedAt.isBefore(completedAt)) {
            throw new DomainRuleViolation("updatedAt must not precede completedAt");
        }
        if (status == LearningPathItemStatus.COMPLETED && !practiceVerified) {
            throw new DomainRuleViolation("completed item needs practice evidence");
        }
        if (status == LearningPathItemStatus.COMPLETED && completedAt == null) {
            throw new DomainRuleViolation("completed item needs completedAt");
        }
    }

    private static <T> T throwRule(String message) {
        throw new DomainRuleViolation(message);
    }

    public static LearningPathItem pending(String learnUnitCode, int sequence, Instant at) {
        return new LearningPathItem(
                null,
                learnUnitCode,
                sequence,
                LearningPathItemStatus.PENDING,
                false,
                null,
                null,
                null,
                at);
    }

    public static LearningPathItem current(String learnUnitCode, int sequence, Instant at) {
        return pending(learnUnitCode, sequence, at).start(at);
    }

    public LearningPathItem withId(long persistedId) {
        return new LearningPathItem(
                DomainChecks.id(persistedId, "id"),
                learnUnitCode,
                sequence,
                status,
                practiceVerified,
                passReason,
                startedAt,
                completedAt,
                updatedAt);
    }

    /** 返回当前路径项的只读掌握投影；掌握状态由已验证 Practice 的完成条件决定。 */
    public Mastery mastery() {
        return new Mastery(status == LearningPathItemStatus.COMPLETED);
    }

    /** PENDING 或可恢复的 SKIPPED 才能进入 CURRENT；COMPLETED 永不回退。 */
    public LearningPathItem start(Instant at) {
        DomainChecks.time(at, "at");
        return switch (status) {
            case PENDING, SKIPPED -> new LearningPathItem(
                    id,
                    learnUnitCode,
                    sequence,
                    LearningPathItemStatus.CURRENT,
                    practiceVerified,
                    passReason,
                    startedAt == null ? at : startedAt,
                    null,
                    at);
            case CURRENT -> this;
            case COMPLETED -> throw new DomainRuleViolation("completed item cannot be started: " + learnUnitCode);
        };
    }

    /** 跳过只关闭当前项，保留已有 Practice 证据，之后仍可恢复。 */
    public LearningPathItem skip(Instant at) {
        DomainChecks.time(at, "at");
        if (status != LearningPathItemStatus.CURRENT) {
            throw new DomainRuleViolation("only current item can be skipped: " + learnUnitCode);
        }
        return new LearningPathItem(
                id,
                learnUnitCode,
                sequence,
                LearningPathItemStatus.SKIPPED,
                practiceVerified,
                passReason,
                startedAt,
                null,
                at);
    }

    public LearningPathItem recordPracticeVerified(Instant at) {
        requireCurrent();
        DomainChecks.time(at, "at");
        LearningPathItem next = new LearningPathItem(
                id,
                learnUnitCode,
                sequence,
                status,
                true,
                passReason,
                startedAt,
                completedAt,
                at);
        return next.complete(at, "PRACTICE_EVIDENCE");
    }

    private LearningPathItem complete(Instant at, String reason) {
        return new LearningPathItem(
                id,
                learnUnitCode,
                sequence,
                LearningPathItemStatus.COMPLETED,
                practiceVerified,
                reason,
                startedAt,
                at,
                at);
    }

    private void requireCurrent() {
        if (status != LearningPathItemStatus.CURRENT) {
            throw new DomainRuleViolation("item is not current: " + learnUnitCode);
        }
    }
}
