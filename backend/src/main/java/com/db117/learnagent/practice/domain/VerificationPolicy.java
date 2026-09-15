package com.db117.learnagent.practice.domain;

import com.db117.learnagent.shared.domain.DomainRuleViolation;

/**
 * PracticeTask 的固定验收条件；不允许用任意 Map 绕过明确的检查语义。
 *
 * @param requireCompile 是否必须编译通过
 * @param requireTests 是否必须有通过的测试且至少执行一个测试
 * @param requireLint 是否必须通过 Lint
 * @param requireRuntime 是否必须运行通过
 */
public record VerificationPolicy(
        boolean requireCompile,
        boolean requireTests,
        boolean requireLint,
        boolean requireRuntime) {

    public VerificationPolicy {
        if (!requireCompile && !requireTests && !requireLint && !requireRuntime) {
            throw new DomainRuleViolation("verification policy must require at least one check");
        }
    }

    public boolean accepts(PracticeEvidence evidence) {
        // Evidence 只描述客观结果；是否达标由任务声明的必需检查项决定。
        if (requireCompile && !evidence.compilePassed()) {
            return false;
        }
        if (requireTests && (!evidence.testsPassed() || evidence.testCount() <= 0)) {
            return false;
        }
        if (requireLint && !evidence.lintPassed()) {
            return false;
        }
        return !requireRuntime || evidence.runtimeResult() == RuntimeResult.PASSED;
    }
}
