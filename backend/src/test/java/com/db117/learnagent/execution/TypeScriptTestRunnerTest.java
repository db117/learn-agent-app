package com.db117.learnagent.execution;

import com.db117.learnagent.workspace.domain.Workspace;
import com.db117.learnagent.workspace.domain.WorkspaceKind;
import com.db117.learnagent.workspace.domain.WorkspaceReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TypeScriptTestRunnerTest {
    @TempDir
    Path root;

    @Test
    void parsesVitestPassedSummaryAndBuildsRunTestsRequest() {
        var expectedWorkspace = workspace();
        var execution = new ExecutionResult(true, 0, """
                stdout:
                Test Files  1 passed (1)
                     Tests  4 passed (4)
                stderr:
                """, Duration.ofMillis(8));
        var runner = new TypeScriptTestRunner((actualWorkspace, request) -> {
            assertEquals(expectedWorkspace, actualWorkspace);
            assertEquals(ExecutionOperation.RUN_TESTS, request.operation());
            assertEquals(List.of("src/index.test.ts"), request.arguments());
            return execution;
        });

        var result = runner.run(expectedWorkspace, List.of("src/index.test.ts"));

        assertEquals(execution, result.execution());
        assertEquals(4, result.testCount());
        assertTrue(result.passed());
    }

    @Test
    void keepsFailureAndCountsAllPassedAndFailedTests() {
        var execution = new ExecutionResult(false, 1, """
                stdout:
                Test Files  1 failed | 1 passed (2)
                     Tests  1 failed | 2 passed (3)
                stderr:
                """, Duration.ofMillis(8));

        var result = new TypeScriptTestRunner((workspace, request) -> execution)
                .run(workspace(), List.of());

        assertEquals(3, result.testCount());
        assertFalse(result.passed());
    }

    @Test
    void treatsMissingTestSummaryAsNotPassed() {
        var execution = new ExecutionResult(true, 0, "stdout:\nNo test files found\nstderr:\n", Duration.ZERO);

        var result = new TypeScriptTestRunner((workspace, request) -> execution)
                .run(workspace(), List.of());

        assertEquals(0, result.testCount());
        assertFalse(result.passed());
    }

    @Test
    void ignoresVitestTerminalColorCodesWhenCountingTests() {
        var execution = new ExecutionResult(true, 0, """
                stdout:
                \u001b[2m      Tests \u001b[22m \u001b[1m\u001b[32m1 passed\u001b[39m\u001b[90m (1)\u001b[39m
                stderr:
                """, Duration.ZERO);

        var result = new TypeScriptTestRunner((workspace, request) -> execution)
                .run(workspace(), List.of());

        assertEquals(1, result.testCount());
        assertTrue(result.passed());
    }

    private Workspace workspace() {
        return new Workspace(new WorkspaceReference(WorkspaceKind.LEARNING, 1), root);
    }
}
