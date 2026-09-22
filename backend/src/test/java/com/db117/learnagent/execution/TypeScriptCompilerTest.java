package com.db117.learnagent.execution;

import com.db117.learnagent.workspace.domain.Workspace;
import com.db117.learnagent.workspace.domain.WorkspaceKind;
import com.db117.learnagent.workspace.domain.WorkspaceReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeScriptCompilerTest {
    @TempDir
    Path root;

    @Test
    void convertsCommonTscDiagnosticsAndPreservesExecutionResult() {
        ExecutionResult execution = new ExecutionResult(false, 2, """
                stdout:
                
                stderr:
                src/index.ts(1,7): error TS2322: Type 'string' is not assignable to type 'number'.
                src/other.ts:4:2 - warning TS6133: 'unused' is declared but its value is never read.
                Found 2 errors.
                """, Duration.ofMillis(12));
        Workspace expectedWorkspace = workspace();
        TypeScriptCompiler compiler = new TypeScriptCompiler((actualWorkspace, request) -> {
            assertEquals(expectedWorkspace, actualWorkspace);
            assertEquals(ExecutionOperation.COMPILE, request.operation());
            assertEquals(List.of("src/index.ts"), request.arguments());
            return execution;
        });

        TypeScriptCompileResult result = compiler.compile(expectedWorkspace, List.of("src/index.ts"));

        assertEquals(execution, result.execution());
        assertEquals(2, result.diagnostics().size());
        TypeScriptDiagnostic error = result.diagnostics().getFirst();
        assertEquals("src/index.ts", error.file());
        assertEquals(1, error.line());
        assertEquals(7, error.column());
        assertEquals("TS2322", error.code());
        assertEquals(TypeScriptDiagnostic.Severity.ERROR, error.severity());
        assertTrue(error.message().contains("not assignable"));
        TypeScriptDiagnostic warning = result.diagnostics().get(1);
        assertEquals(TypeScriptDiagnostic.Severity.WARNING, warning.severity());
    }

    @Test
    void returnsNoDiagnosticsForSuccessfulCompilation() {
        ExecutionResult execution = new ExecutionResult(true, 0, "stdout:\n\nstderr:\n", Duration.ZERO);
        TypeScriptCompileResult result = new TypeScriptCompiler((workspace, request) -> execution)
                .compile(workspace(), List.of());

        assertTrue(result.execution().success());
        assertTrue(result.diagnostics().isEmpty());
    }

    private Workspace workspace() {
        return new Workspace(new WorkspaceReference(WorkspaceKind.LEARNING, 1), root);
    }
}
