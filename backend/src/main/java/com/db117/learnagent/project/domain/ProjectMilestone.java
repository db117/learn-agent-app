package com.db117.learnagent.project.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Project 内的领域进度节点；完成必须有通过的 ProjectEvidence。 */
public final class ProjectMilestone {
    private final Long id;
    private final String code;
    private final String title;
    private final int sequence;
    private final ProjectMilestoneStatus status;
    private final List<ProjectEvidence> evidence;

    private ProjectMilestone(
            Long id,
            String code,
            String title,
            int sequence,
            ProjectMilestoneStatus status,
            List<ProjectEvidence> evidence) {
        if (id != null && id <= 0) {
            throw new DomainRuleViolation("id must be positive");
        }
        this.id = id;
        this.code = DomainChecks.text(code, "code");
        this.title = DomainChecks.text(title, "title");
        if (sequence < 0) {
            throw new DomainRuleViolation("sequence must not be negative");
        }
        this.sequence = sequence;
        this.status = status == null ? throwRule("status must not be null") : status;
        var evidenceList = evidence == null ? List.<ProjectEvidence>of() : evidence;
        if (evidenceList.stream().anyMatch(Objects::isNull)) {
            throw new DomainRuleViolation("evidence must not contain null");
        }
        this.evidence = List.copyOf(evidenceList);
        if (status == ProjectMilestoneStatus.COMPLETED
                && this.evidence.stream().noneMatch(ProjectEvidence::passed)) {
            throw new DomainRuleViolation("completed milestone needs passing evidence");
        }
    }

    private static <T> T throwRule(String message) {
        throw new DomainRuleViolation(message);
    }

    public static ProjectMilestone create(String code, String title, int sequence) {
        return new ProjectMilestone(null, code, title, sequence, ProjectMilestoneStatus.PENDING, List.of());
    }

    /** 仅由持久化适配器恢复已有 Milestone，证据和状态仍经过同一组规则校验。 */
    public static ProjectMilestone reconstitute(
            long id,
            String code,
            String title,
            int sequence,
            ProjectMilestoneStatus status,
            List<ProjectEvidence> evidence) {
        return new ProjectMilestone(
                DomainChecks.id(id, "id"), code, title, sequence, status, evidence);
    }

    public Long id() {
        return id;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public int sequence() {
        return sequence;
    }

    public ProjectMilestoneStatus status() {
        return status;
    }

    public List<ProjectEvidence> evidence() {
        return evidence;
    }

    public ProjectMilestone start() {
        if (status == ProjectMilestoneStatus.COMPLETED) {
            throw new DomainRuleViolation("completed milestone cannot be started: " + code);
        }
        return new ProjectMilestone(id, code, title, sequence, ProjectMilestoneStatus.IN_PROGRESS, evidence);
    }

    public ProjectMilestone recordEvidence(ProjectEvidence nextEvidence) {
        // 失败证据也追加保存；只有通过证据才改变 Milestone 状态。
        if (status == ProjectMilestoneStatus.COMPLETED) {
            throw new DomainRuleViolation("completed milestone cannot receive evidence: " + code);
        }
        if (nextEvidence == null) {
            throw new DomainRuleViolation("evidence must not be null");
        }
        var nextEvidenceList = new ArrayList<>(evidence);
        nextEvidenceList.add(nextEvidence);
        var nextStatus = nextEvidence.passed()
                ? ProjectMilestoneStatus.COMPLETED
                : ProjectMilestoneStatus.IN_PROGRESS;
        return new ProjectMilestone(id, code, title, sequence, nextStatus, nextEvidenceList);
    }

    public ProjectMilestone withId(long persistedId) {
        return new ProjectMilestone(
                DomainChecks.id(persistedId, "id"), code, title, sequence, status, evidence);
    }
}
