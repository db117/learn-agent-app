package com.db117.learnagent.project.domain;

import com.db117.learnagent.shared.domain.DomainChecks;

import java.time.Instant;

/**
 * ProjectMilestone 的不可变完成证据，引用 Workspace/Artifact 而非完整日志。
 *
 * @param artifactReference 产物或 ProjectWorkspace 快照的外部引用
 * @param verificationSummary 面向领域的验证摘要，不保存完整 stdout/stderr
 * @param passed 本次验证是否通过
 * @param verifiedAt 本次验证完成时间
 */
public record ProjectEvidence(
        String artifactReference,
        String verificationSummary,
        boolean passed,
        Instant verifiedAt) {
    public ProjectEvidence {
        artifactReference = DomainChecks.text(artifactReference, "artifactReference");
        verificationSummary = DomainChecks.text(verificationSummary, "verificationSummary");
        verifiedAt = DomainChecks.time(verifiedAt, "verifiedAt");
    }
}
