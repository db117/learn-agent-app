package com.db117.learnagent.learning.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.time.Instant;

/**
 * Learner 的目标描述；拆解、学习内容和评估不属于 Journey 本身。
 *
 * @param id SQLite 自增主键；首次创建时为 {@code null}
 * @param learnerId Journey 所属 Learner 的主键
 * @param goalDescription 用户确认的目标原文，不能被模型结果覆盖
 * @param status 目标的生命周期状态
 * @param createdAt Journey 创建时间
 * @param archivedAt 归档时间；只有归档 Journey 才允许填写
 * @param learningJourneyId 唯一的生成学习路径主键；规划完成前为空
 * @param current 当前用户选择的 Journey；该选择属于 Domain State
 */
public record Journey(
        Long id,
        long learnerId,
        String goalDescription,
        JourneyStatus status,
        Instant createdAt,
        Instant archivedAt,
        Long learningJourneyId,
        boolean current) {

    public Journey {
        if (id != null && id <= 0) {
            throw new DomainRuleViolation("id must be positive");
        }
        DomainChecks.id(learnerId, "learnerId");
        goalDescription = DomainChecks.text(goalDescription, "goalDescription");
        status = status == null ? throwRule("status must not be null") : status;
        createdAt = DomainChecks.time(createdAt, "createdAt");
        if (learningJourneyId != null && learningJourneyId <= 0) {
            throw new DomainRuleViolation("learningJourneyId must be positive");
        }
        if (status == JourneyStatus.ACTIVE && archivedAt != null) {
            throw new DomainRuleViolation("active journey cannot have archivedAt");
        }
        if (status == JourneyStatus.ARCHIVED && archivedAt == null) {
            throw new DomainRuleViolation("archived journey needs archivedAt");
        }
        if (archivedAt != null && archivedAt.isBefore(createdAt)) {
            throw new DomainRuleViolation("archivedAt must not precede createdAt");
        }
        if (current && status != JourneyStatus.ACTIVE) {
            throw new DomainRuleViolation("archived journey cannot be current");
        }
    }

    public static Journey create(long learnerId, String goalDescription, Instant createdAt) {
        return new Journey(null, learnerId, goalDescription, JourneyStatus.ACTIVE, createdAt, null, null, false);
    }

    public Journey select() {
        if (status != JourneyStatus.ACTIVE) {
            throw new DomainRuleViolation("only active journey can be selected");
        }
        return copy(true, status, archivedAt, learningJourneyId);
    }

    public Journey withId(long persistedId) {
        return new Journey(
                DomainChecks.id(persistedId, "id"),
                learnerId,
                goalDescription,
                status,
                createdAt,
                archivedAt,
                learningJourneyId,
                current);
    }

    public Journey deselect() {
        return copy(false, status, archivedAt, learningJourneyId);
    }

    public Journey archive(Instant at) {
        DomainChecks.time(at, "archivedAt");
        if (current) {
            throw new DomainRuleViolation("current journey must be deselected before archiving");
        }
        return copy(false, JourneyStatus.ARCHIVED, at, learningJourneyId);
    }

    public Journey attachLearningJourney(long nextLearningJourneyId) {
        DomainChecks.id(nextLearningJourneyId, "learningJourneyId");
        return copy(current, status, archivedAt, nextLearningJourneyId);
    }

    private Journey copy(
            boolean nextCurrent,
            JourneyStatus nextStatus,
            Instant nextArchivedAt,
            Long nextLearningJourneyId) {
        return new Journey(
                id,
                learnerId,
                goalDescription,
                nextStatus,
                createdAt,
                nextArchivedAt,
                nextLearningJourneyId,
                nextCurrent);
    }

    private static <T> T throwRule(String message) {
        throw new DomainRuleViolation(message);
    }
}
