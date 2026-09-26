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
 * @param assessmentId 经学习者确认并完成当前项的 Tutor 评估 ID；没有时为空
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
        Long assessmentId,
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
        if (assessmentId != null && assessmentId <= 0) {
            throw new DomainRuleViolation("assessmentId must be positive");
        }
        if (passReason != null && passReason.isBlank()) {
            passReason = null;
        }
        if (startedAt != null && updatedAt != null && updatedAt.isBefore(startedAt)) {
            throw new DomainRuleViolation("updatedAt must not precede startedAt");
        }
        if (completedAt != null && updatedAt != null && updatedAt.isBefore(completedAt)) {
            throw new DomainRuleViolation("updatedAt must not precede completedAt");
        }
        if (status == LearningPathItemStatus.COMPLETED && !practiceVerified && assessmentId == null) {
            throw new DomainRuleViolation("completed item needs verified practice or accepted assessment");
        }
        if (assessmentId != null && status != LearningPathItemStatus.COMPLETED) {
            throw new DomainRuleViolation("accepted assessment must belong to a completed item");
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
                assessmentId,
                passReason,
                startedAt,
                completedAt,
                updatedAt);
    }

    public LearningPathItem withSequence(int nextSequence) {
        return new LearningPathItem(id, learnUnitCode, nextSequence, status, practiceVerified,
                assessmentId, passReason, startedAt, completedAt, updatedAt);
    }

    /** 路线重排时保留非完成项目的练习事实和开始时间。 */
    public LearningPathItem replan(
            int nextSequence,
            LearningPathItemStatus nextStatus,
            Instant at) {
        DomainChecks.time(at, "at");
        if (status == LearningPathItemStatus.COMPLETED
                || nextStatus == LearningPathItemStatus.COMPLETED) {
            throw new DomainRuleViolation("completed item cannot be changed by route proposal: " + learnUnitCode);
        }
        if (nextStatus != LearningPathItemStatus.PENDING
                && nextStatus != LearningPathItemStatus.CURRENT
                && nextStatus != LearningPathItemStatus.SKIPPED) {
            throw new DomainRuleViolation("invalid route proposal status: " + nextStatus);
        }
        return new LearningPathItem(
                id,
                learnUnitCode,
                nextSequence,
                nextStatus,
                practiceVerified,
                null,
                null,
                startedAt == null && nextStatus == LearningPathItemStatus.CURRENT ? at : startedAt,
                null,
                at);
    }

    /** 返回当前路径项的只读掌握投影；Tutor 评估只有经学习者确认后才完成路径项。 */
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
                    null,
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
                null,
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
                null,
                passReason,
                startedAt,
                completedAt,
                at);
        return next.complete(at, "PRACTICE_EVIDENCE");
    }

    /** 保存学习者确认通过的 Tutor 评估；不修改不可变 PracticeEvidence。 */
    public LearningPathItem acceptAssessment(long acceptedAssessmentId, boolean objectivePracticeVerified, Instant at) {
        requireCurrent();
        DomainChecks.id(acceptedAssessmentId, "acceptedAssessmentId");
        DomainChecks.time(at, "at");
        return new LearningPathItem(
                id,
                learnUnitCode,
                sequence,
                LearningPathItemStatus.COMPLETED,
                practiceVerified || objectivePracticeVerified,
                acceptedAssessmentId,
                "AGENT_ASSESSMENT",
                startedAt,
                at,
                at);
    }

    private LearningPathItem complete(Instant at, String reason) {
        return new LearningPathItem(
                id,
                learnUnitCode,
                sequence,
                LearningPathItemStatus.COMPLETED,
                practiceVerified,
                null,
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
