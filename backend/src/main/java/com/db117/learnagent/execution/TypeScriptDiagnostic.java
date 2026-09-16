package com.db117.learnagent.execution;

import java.util.Objects;

/** TypeScript 编译器输出的稳定诊断字段，不保存代码片段或完整日志。 */
public record TypeScriptDiagnostic(
        /** 产生诊断的 Workspace 相对文件路径。 */
        String file,
        /** 诊断所在行号，从 1 开始。 */
        int line,
        /** 诊断所在列号，从 1 开始。 */
        int column,
        /** TypeScript 诊断编号，例如 {@code TS2322}。 */
        String code,
        /** 诊断严重级别。 */
        Severity severity,
        /** 面向 Tutor 和 UI 的单条诊断消息。 */
        String message) {

    public TypeScriptDiagnostic {
        file = text(file, "file");
        code = text(code, "code");
        severity = Objects.requireNonNull(severity, "severity must not be null");
        message = text(message, "message");
        if (line <= 0) {
            throw new IllegalArgumentException("line must be positive");
        }
        if (column <= 0) {
            throw new IllegalArgumentException("column must be positive");
        }
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    /** tsc 文本格式可表达的诊断严重级别。 */
    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }
}
