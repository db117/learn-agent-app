package com.db117.learnagent.practice.domain;

import com.db117.learnagent.shared.domain.DomainChecks;
import com.db117.learnagent.shared.domain.DomainRuleViolation;

import java.time.Instant;
import java.util.List;

/**
 * Practice 的不可变客观证据，不保存源码和完整执行日志。
 *
 * @param compilePassed 编译检查是否通过
 * @param testsPassed 测试检查是否通过
 * @param testCount 实际执行的测试数量；要求测试时必须大于零
 * @param lintPassed Lint 检查是否通过
 * @param runtimeResult 运行检查结果；未要求或未执行时可为 {@code NOT_RUN}
 * @param submittedFiles 提交文件的路径或 Artifact 引用，不是文件内容
 * @param verifiedAt 产生验证结果的时间；没有结果时可为空
 */
public record PracticeEvidence(
        boolean compilePassed,
        boolean testsPassed,
        int testCount,
        boolean lintPassed,
        RuntimeResult runtimeResult,
        List<String> submittedFiles,
        Instant verifiedAt) {

    public PracticeEvidence {
        if (testCount < 0) {
            throw new DomainRuleViolation("testCount must not be negative");
        }
        runtimeResult = runtimeResult == null ? throwRule("runtimeResult must not be null") : runtimeResult;
        if (submittedFiles == null) {
            throw new DomainRuleViolation("submittedFiles must not be null");
        }
        submittedFiles = submittedFiles.stream()
                .map(file -> DomainChecks.text(file, "submittedFile"))
                .toList();
        if (verifiedAt != null) {
            DomainChecks.time(verifiedAt, "verifiedAt");
        }
    }

    private static <T> T throwRule(String message) {
        throw new DomainRuleViolation(message);
    }

    public boolean isVerified(VerificationPolicy policy) {
        return verifiedAt != null && policy.accepts(this);
    }
}
