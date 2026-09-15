package com.db117.learnagent.learning.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/**
 * Assessment 的追加历史；提交和评估分成两个状态，支持编码题等待执行结果。
 *
 * @param id SQLite 自增主键；未保存的提交为 {@code null}
 * @param journeyId 所属 LearningJourney 的主键，禁止跨 Journey 使用
 * @param learnUnitCode 本次评估对应的 LearnUnit 编码
 * @param status {@code SUBMITTED} 表示等待结果，{@code EVALUATED} 表示已有确定性评分
 * @param answers 本次提交的不可变答案快照
 * @param score 评估分数（0-100）；{@code SUBMITTED} 时为空
 * @param passed 是否达到固定及格线；{@code SUBMITTED} 时为空
 * @param submittedAt 学习者提交时间
 * @param evaluatedAt 评估完成时间；{@code SUBMITTED} 时为空，且不能早于提交时间
 */
public record AssessmentAttempt(
        Long id,
        long journeyId,
        String learnUnitCode,
        AssessmentAttemptStatus status,
        List<Answer> answers,
        Integer score,
        Boolean passed,
        Instant submittedAt,
        Instant evaluatedAt) {

    public AssessmentAttempt {
        if (id != null && id <= 0) {
            throw new DomainRuleViolation("id must be positive");
        }
        DomainChecks.id(journeyId, "journeyId");
        learnUnitCode = DomainChecks.text(learnUnitCode, "learnUnitCode");
        status = status == null ? throwRule("status must not be null") : status;
        if (answers == null) {
            throw new DomainRuleViolation("answers must not be null");
        }
        answers = List.copyOf(answers);
        var questionCodes = new HashSet<String>();
        for (Answer answer : answers) {
            if (!questionCodes.add(answer.questionCode())) {
                throw new DomainRuleViolation("answers must contain one answer per question");
            }
        }
        submittedAt = DomainChecks.time(submittedAt, "submittedAt");
        if (status == AssessmentAttemptStatus.SUBMITTED) {
            if (score != null || passed != null || evaluatedAt != null) {
                throw new DomainRuleViolation("SUBMITTED attempt cannot contain an evaluation");
            }
        } else {
            if (score == null || passed == null || evaluatedAt == null) {
                throw new DomainRuleViolation("EVALUATED attempt needs score, passed and evaluatedAt");
            }
            DomainChecks.score(score);
            if (passed != (score >= 70)) {
                throw new DomainRuleViolation("passed must match the default passing score");
            }
            if (evaluatedAt.isBefore(submittedAt)) {
                throw new DomainRuleViolation("evaluatedAt must not precede submittedAt");
            }
        }
    }

    private static <T> T throwRule(String message) {
        throw new DomainRuleViolation(message);
    }

    public static AssessmentAttempt submitted(
            long journeyId, String learnUnitCode, List<Answer> answers, Instant submittedAt) {
        return new AssessmentAttempt(
                null,
                journeyId,
                learnUnitCode,
                AssessmentAttemptStatus.SUBMITTED,
                answers,
                null,
                null,
                submittedAt,
                null);
    }

    public AssessmentAttempt evaluate(int score, Instant evaluatedAt) {
        DomainChecks.score(score);
        return new AssessmentAttempt(
                id,
                journeyId,
                learnUnitCode,
                AssessmentAttemptStatus.EVALUATED,
                answers,
                score,
                score >= 70,
                submittedAt,
                evaluatedAt);
    }

    public AssessmentAttempt withId(long persistedId) {
        return new AssessmentAttempt(
                DomainChecks.id(persistedId, "id"),
                journeyId,
                learnUnitCode,
                status,
                answers,
                score,
                passed,
                submittedAt,
                evaluatedAt);
    }
}
