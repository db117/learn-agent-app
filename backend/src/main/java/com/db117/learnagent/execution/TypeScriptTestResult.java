package com.db117.learnagent.execution;

import java.util.Objects;

/** TypeScript 测试的一次不可变结果，保留执行摘要和实际测试数量。 */
public record TypeScriptTestResult(
        /** Vitest 进程的执行结果和有限摘要。 */
        ExecutionResult execution,
        /** Vitest 汇总报告中的实际测试数量。 */
        int testCount) {

    public TypeScriptTestResult {
        execution = Objects.requireNonNull(execution, "execution must not be null");
        if (testCount < 0) {
            throw new IllegalArgumentException("testCount must not be negative");
        }
    }

    /** 只有进程成功且至少执行一个测试时，测试结果才算通过。 */
    public boolean passed() {
        return execution.success() && testCount > 0;
    }
}
