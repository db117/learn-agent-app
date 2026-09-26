package com.db117.learnagent.practice.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.time.Instant;

/**
 * Tutor 对一次客观 PracticeAttempt 的不可变评估；通过后仍需学习者确认才可完成课程。
 *
 * @param id SQLite 自增主键；尚未保存时为空
 * @param journeyId 所属父 Journey
 * @param learnUnitId 被评估的 LearnUnit
 * @param practiceTaskId 产生本次客观证据的 PracticeTask
 * @param practiceAttemptId 被评估的不可变 PracticeAttempt
 * @param verdict Tutor 对当前学习进展的结论
 * @param rationale 面向学习者展示的结论依据，不是模型私有推理
 * @param workspaceDigest 本次客观验证时学习目录的 SHA-256 摘要
 * @param createdAt 评估生成时间
 */
public record PracticeAssessment(
        Long id,
        long journeyId,
        long learnUnitId,
        long practiceTaskId,
        long practiceAttemptId,
        PracticeAssessmentVerdict verdict,
        String rationale,
        String workspaceDigest,
        Instant createdAt) {

    public PracticeAssessment {
        if (id != null && id <= 0) {
            throw new DomainRuleViolation("id must be positive");
        }
        if (journeyId <= 0 || learnUnitId <= 0 || practiceTaskId <= 0 || practiceAttemptId <= 0) {
            throw new DomainRuleViolation("assessment references must be positive");
        }
        verdict = verdict == null ? throwRule("verdict must not be null") : verdict;
        rationale = DomainChecks.text(rationale, "rationale");
        if (rationale.length() > 4_000) {
            throw new DomainRuleViolation("rationale must not exceed 4000 characters");
        }
        workspaceDigest = DomainChecks.text(workspaceDigest, "workspaceDigest");
        if (!workspaceDigest.matches("[0-9a-f]{64}")) {
            throw new DomainRuleViolation("workspaceDigest must be a lowercase SHA-256 digest");
        }
        createdAt = DomainChecks.time(createdAt, "createdAt");
    }

    public static PracticeAssessment create(
            long journeyId,
            long learnUnitId,
            long practiceTaskId,
            long practiceAttemptId,
            PracticeAssessmentVerdict verdict,
            String rationale,
            String workspaceDigest,
            Instant createdAt) {
        return new PracticeAssessment(null, journeyId, learnUnitId, practiceTaskId, practiceAttemptId,
                verdict, rationale, workspaceDigest, createdAt);
    }

    public PracticeAssessment withId(long persistedId) {
        return new PracticeAssessment(DomainChecks.id(persistedId, "id"), journeyId, learnUnitId,
                practiceTaskId, practiceAttemptId, verdict, rationale, workspaceDigest, createdAt);
    }

    private static <T> T throwRule(String message) {
        throw new DomainRuleViolation(message);
    }
}
