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
 * @param masteryScore 当前 Journey 内的掌握分数，来源于最佳评估结果
 * @param bestScore 当前 Journey 内历史最高评估分数
 * @param practiceVerified 是否存在满足 VerificationPolicy 的 PracticeEvidence
 * @param assessmentPassed 是否存在达到固定及格线的评估结果
 * @param attemptCount 当前 LearnUnit 的评估尝试次数
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
        int masteryScore,
        int bestScore,
        boolean practiceVerified,
        boolean assessmentPassed,
        int attemptCount,
        String passReason,
        Instant startedAt,
        Instant completedAt,
        Instant updatedAt) {

    /** Step 2 的统一及格线；Assessment 和 Mastery 使用同一个确定性阈值。 */
    public static final int DEFAULT_PASSING_SCORE = 70;

    public LearningPathItem {
        if (id != null && id <= 0) {
            throw new DomainRuleViolation("id must be positive");
        }
        learnUnitCode = DomainChecks.text(learnUnitCode, "learnUnitCode");
        if (sequence < 0) {
            throw new DomainRuleViolation("sequence must not be negative");
        }
        status = status == null ? throwRule("status must not be null") : status;
        DomainChecks.score(masteryScore);
        DomainChecks.score(bestScore);
        if (masteryScore != bestScore) {
            throw new DomainRuleViolation("masteryScore must equal bestScore");
        }
        if (assessmentPassed && bestScore < DEFAULT_PASSING_SCORE) {
            throw new DomainRuleViolation("assessmentPassed needs a passing bestScore");
        }
        if (attemptCount < 0) {
            throw new DomainRuleViolation("attemptCount must not be negative");
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
        if (status == LearningPathItemStatus.COMPLETED
                && (!assessmentPassed || !practiceVerified || bestScore < DEFAULT_PASSING_SCORE)) {
            throw new DomainRuleViolation("completed item needs passing assessment and practice evidence");
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
                0,
                0,
                false,
                false,
                0,
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
                masteryScore,
                bestScore,
                practiceVerified,
                assessmentPassed,
                attemptCount,
                passReason,
                startedAt,
                completedAt,
                updatedAt);
    }

    /** 返回当前路径项的只读掌握投影；掌握状态仍由本路径项的完成条件决定。 */
    public Mastery mastery() {
        return new Mastery(masteryScore, status == LearningPathItemStatus.COMPLETED);
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
                    masteryScore,
                    bestScore,
                    practiceVerified,
                    assessmentPassed,
                    attemptCount,
                    passReason,
                    startedAt == null ? at : startedAt,
                    null,
                    at);
            case CURRENT -> this;
            case COMPLETED -> throw new DomainRuleViolation("completed item cannot be started: " + learnUnitCode);
        };
    }

    /** 跳过只关闭当前项，保留已有 Attempt 和 Evidence，之后仍可恢复。 */
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
                masteryScore,
                bestScore,
                practiceVerified,
                assessmentPassed,
                attemptCount,
                passReason,
                startedAt,
                null,
                at);
    }

    public LearningPathItem recordAssessment(int score, int passingScore, Instant at) {
        // Assessment 历史追加到 Attempt；这里仅更新 Journey 的 best score 和完成条件。
        requireCurrent();
        DomainChecks.score(score);
        DomainChecks.score(passingScore);
        if (passingScore != DEFAULT_PASSING_SCORE) {
            throw new DomainRuleViolation("passingScore must be 70");
        }
        DomainChecks.time(at, "at");
        int nextBestScore = Math.max(bestScore, score);
        boolean nextAssessmentPassed = assessmentPassed || score >= passingScore;
        var next = new LearningPathItem(
                id,
                learnUnitCode,
                sequence,
                status,
                nextBestScore,
                nextBestScore,
                practiceVerified,
                nextAssessmentPassed,
                attemptCount + 1,
                passReason,
                startedAt,
                completedAt,
                at);
        return nextAssessmentPassed && practiceVerified ? next.complete(at) : next;
    }

    public LearningPathItem recordPracticeVerified(Instant at) {
        // Practice 证据只影响当前 Journey 项，不直接让 Agent 或其他 Journey 改写 mastery。
        requireCurrent();
        DomainChecks.time(at, "at");
        var next = new LearningPathItem(
                id,
                learnUnitCode,
                sequence,
                status,
                masteryScore,
                bestScore,
                true,
                assessmentPassed,
                attemptCount,
                passReason,
                startedAt,
                completedAt,
                at);
        return assessmentPassed && bestScore >= DEFAULT_PASSING_SCORE ? next.complete(at) : next;
    }

    private LearningPathItem complete(Instant at) {
        return new LearningPathItem(
                id,
                learnUnitCode,
                sequence,
                LearningPathItemStatus.COMPLETED,
                masteryScore,
                bestScore,
                practiceVerified,
                true,
                attemptCount,
                "ASSESSMENT_AND_PRACTICE",
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
