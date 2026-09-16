package com.db117.learnagent.execution;

import com.db117.learnagent.workspace.domain.Workspace;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** 通过 ExecutionEnvironment 执行 Vitest，并提取测试总数。 */
@ApplicationScoped
public final class TypeScriptTestRunner {
    private static final Pattern TEST_SUMMARY = Pattern.compile("^\\s*Tests\\b.*\\((\\d+)\\)\\s*$");

    private final ExecutionEnvironment executionEnvironment;

    public TypeScriptTestRunner(ExecutionEnvironment executionEnvironment) {
        this.executionEnvironment = Objects.requireNonNull(
                executionEnvironment, "executionEnvironment must not be null");
    }

    /** 执行一次受限 Vitest 测试，并从汇总行解析实际测试数量。 */
    public TypeScriptTestResult run(Workspace workspace, List<String> testPaths) {
        var execution = executionEnvironment.execute(
                workspace, new ExecutionRequest(ExecutionOperation.RUN_TESTS, testPaths));
        return new TypeScriptTestResult(execution, parseTestCount(execution.summary()));
    }

    private static int parseTestCount(String summary) {
        if (summary == null || summary.isBlank()) {
            return 0;
        }
        var testCount = 0;
        for (var line : summary.lines().toList()) {
            var matcher = TEST_SUMMARY.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            try {
                testCount = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                // 忽略异常的汇总行，避免日志内容破坏测试结果读取。
            }
        }
        return testCount;
    }
}
