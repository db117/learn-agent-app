package com.db117.learnagent.project.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/** Project 聚合根；一个 Journey 只能关联一个 Project。 */
public final class Project {
    private final Long id;
    private final long journeyId;
    private final String title;
    private final ProjectStatus status;
    private final Instant createdAt;
    private final Instant completedAt;
    private final List<ProjectMilestone> milestones;

    private Project(
            Long id,
            long journeyId,
            String title,
            ProjectStatus status,
            Instant createdAt,
            Instant completedAt,
            List<ProjectMilestone> milestones) {
        if (id != null && id <= 0) {
            throw new DomainRuleViolation("id must be positive");
        }
        DomainChecks.id(journeyId, "journeyId");
        this.id = id;
        this.journeyId = journeyId;
        this.title = DomainChecks.text(title, "title");
        this.status = status == null ? throwRule("status must not be null") : status;
        this.createdAt = DomainChecks.time(createdAt, "createdAt");
        if (completedAt != null && completedAt.isBefore(createdAt)) {
            throw new DomainRuleViolation("completedAt must not precede createdAt");
        }
        if (status == ProjectStatus.COMPLETED && completedAt == null) {
            throw new DomainRuleViolation("completed project needs completedAt");
        }
        if (status != ProjectStatus.COMPLETED && completedAt != null) {
            throw new DomainRuleViolation("only completed project may have completedAt");
        }
        this.completedAt = completedAt;
        if (milestones == null || milestones.isEmpty()) {
            throw new DomainRuleViolation("project must contain at least one milestone");
        }
        this.milestones = List.copyOf(milestones);
        var codes = new HashSet<String>();
        for (ProjectMilestone milestone : this.milestones) {
            if (!codes.add(milestone.code())) {
                throw new DomainRuleViolation("milestone codes must be unique");
            }
        }
        if (status == ProjectStatus.COMPLETED
                && this.milestones.stream().anyMatch(milestone ->
                milestone.status() != ProjectMilestoneStatus.COMPLETED)) {
            throw new DomainRuleViolation("completed project needs completed milestones");
        }
    }

    private static <T> T throwRule(String message) {
        throw new DomainRuleViolation(message);
    }

    public static Project create(long journeyId, String title, List<ProjectMilestone> milestones, Instant createdAt) {
        return new Project(null, journeyId, title, ProjectStatus.PLANNED, createdAt, null, milestones);
    }

    /** 仅由持久化适配器恢复已有 Project，恢复时重新校验 Milestone 和完成状态。 */
    public static Project reconstitute(
            long id,
            long journeyId,
            String title,
            ProjectStatus status,
            Instant createdAt,
            Instant completedAt,
            List<ProjectMilestone> milestones) {
        return new Project(
                DomainChecks.id(id, "id"), journeyId, title, status, createdAt, completedAt, milestones);
    }

    public Long id() {
        return id;
    }

    public long journeyId() {
        return journeyId;
    }

    public String title() {
        return title;
    }

    public ProjectStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public List<ProjectMilestone> milestones() {
        return milestones;
    }

    public Project activate() {
        if (status == ProjectStatus.COMPLETED) {
            throw new DomainRuleViolation("completed project cannot be activated");
        }
        return new Project(id, journeyId, title, ProjectStatus.ACTIVE, createdAt, null, milestones);
    }

    public Project startMilestone(String code) {
        if (status != ProjectStatus.ACTIVE) {
            throw new DomainRuleViolation("project must be active before starting a milestone");
        }
        var milestone = milestone(code);
        var nextMilestone = milestone.start();
        return replace(nextMilestone);
    }

    public Project recordEvidence(String code, ProjectEvidence evidence) {
        // Project 的完成由全部 Milestone 的领域证据决定，不接受 Agent 直接标记完成。
        if (status == ProjectStatus.PLANNED) {
            throw new DomainRuleViolation("project must be active before recording evidence");
        }
        var nextProject = replace(milestone(code).recordEvidence(evidence));
        if (nextProject.milestones.stream().allMatch(item -> item.status() == ProjectMilestoneStatus.COMPLETED)) {
            return new Project(
                    id,
                    journeyId,
                    title,
                    ProjectStatus.COMPLETED,
                    createdAt,
                    evidence.verifiedAt(),
                    nextProject.milestones);
        }
        return nextProject;
    }

    public Project withPersistedIds(long persistedId, List<ProjectMilestone> persistedMilestones) {
        return new Project(
                DomainChecks.id(persistedId, "id"),
                journeyId,
                title,
                status,
                createdAt,
                completedAt,
                persistedMilestones);
    }

    private ProjectMilestone milestone(String code) {
        return milestones.stream()
                .filter(item -> item.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new DomainRuleViolation("unknown milestone: " + code));
    }

    private Project replace(ProjectMilestone replacement) {
        return new Project(
                id,
                journeyId,
                title,
                status,
                createdAt,
                completedAt,
                milestones.stream()
                        .map(item -> item.code().equals(replacement.code()) ? replacement : item)
                        .toList());
    }
}
