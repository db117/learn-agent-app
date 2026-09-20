package com.db117.learnagent.execution;

import java.util.List;
import java.util.Objects;

/**
 * TypeScript 编译的一次不可变结果，保留执行摘要和可消费的结构化诊断。
 *
 * @param execution 编译进程的原始执行结果摘要
 * @param diagnostics 从 tsc 摘要解析出的诊断，按输出顺序排列
 */
public record TypeScriptCompileResult(
        ExecutionResult execution,
        List<TypeScriptDiagnostic> diagnostics) {

    public TypeScriptCompileResult {
        execution = Objects.requireNonNull(execution, "execution must not be null");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics must not be null"));
    }
}
