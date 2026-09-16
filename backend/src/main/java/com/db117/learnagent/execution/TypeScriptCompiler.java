package com.db117.learnagent.execution;

import com.db117.learnagent.workspace.domain.Workspace;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** 通过 ExecutionEnvironment 执行 tsc，并把常见诊断首行转换为稳定结构。 */
@ApplicationScoped
public final class TypeScriptCompiler {
    private static final Pattern PARENTHESIZED_DIAGNOSTIC = Pattern.compile(
            "^(.+?)\\((\\d+),(\\d+)\\):\\s*(error|warning|suggestion|message)\\s+(TS\\d+):\\s*(.*)$");
    private static final Pattern COLON_DIAGNOSTIC = Pattern.compile(
            "^(.+?):(\\d+):(\\d+)\\s+-\\s+(error|warning|suggestion|message)\\s+(TS\\d+):\\s*(.*)$");

    private final ExecutionEnvironment executionEnvironment;

    public TypeScriptCompiler(ExecutionEnvironment executionEnvironment) {
        this.executionEnvironment = Objects.requireNonNull(
                executionEnvironment, "executionEnvironment must not be null");
    }

    /** 执行一次受限 TypeScript 编译，并解析 tsc 摘要中的诊断首行。 */
    public TypeScriptCompileResult compile(Workspace workspace, List<String> sourcePaths) {
        var execution = executionEnvironment.execute(
                workspace, new ExecutionRequest(ExecutionOperation.COMPILE, sourcePaths));
        return new TypeScriptCompileResult(execution, parseDiagnostics(execution.summary()));
    }

    private static List<TypeScriptDiagnostic> parseDiagnostics(String summary) {
        if (summary == null || summary.isBlank()) {
            return List.of();
        }
        var diagnostics = new ArrayList<TypeScriptDiagnostic>();
        summary.lines().forEach(line -> {
            var diagnostic = parseDiagnostic(line);
            if (diagnostic != null) {
                diagnostics.add(diagnostic);
            }
        });
        return List.copyOf(diagnostics);
    }

    private static TypeScriptDiagnostic parseDiagnostic(String line) {
        var matcher = PARENTHESIZED_DIAGNOSTIC.matcher(line);
        if (!matcher.matches()) {
            matcher = COLON_DIAGNOSTIC.matcher(line);
            if (!matcher.matches()) {
                return null;
            }
        }
        var message = matcher.group(6).trim();
        if (message.isBlank()) {
            return null;
        }
        try {
            return new TypeScriptDiagnostic(
                    matcher.group(1).trim(),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    matcher.group(5),
                    severity(matcher.group(4)),
                    message);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static TypeScriptDiagnostic.Severity severity(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "error" -> TypeScriptDiagnostic.Severity.ERROR;
            case "warning" -> TypeScriptDiagnostic.Severity.WARNING;
            default -> TypeScriptDiagnostic.Severity.INFO;
        };
    }
}
