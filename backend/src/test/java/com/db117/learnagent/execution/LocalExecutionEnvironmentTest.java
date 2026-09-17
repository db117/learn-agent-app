package com.db117.learnagent.execution;

import com.db117.learnagent.workspace.domain.Workspace;
import com.db117.learnagent.workspace.domain.WorkspaceKind;
import com.db117.learnagent.workspace.domain.WorkspaceReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class LocalExecutionEnvironmentTest {
    @TempDir
    Path root;

    @Test
    void runsProgramInsideWorkspaceAndKeepsOutputSections() throws IOException {
        write("program.mjs", "console.log('ok'); console.error('warning');");

        var result = environment().execute(workspace(),
                new ExecutionRequest(ExecutionOperation.RUN_PROGRAM, List.of("program.mjs")));

        assertTrue(result.success());
        assertEquals(0, result.exitCode());
        assertTrue(result.summary().contains("stdout:\nok"));
        assertTrue(result.summary().contains("stderr:\nwarning"));
        assertFalse(result.duration().isNegative());
    }

    @Test
    void returnsNonZeroExitCodeAndBoundedOutput() throws IOException {
        write("program.mjs", "console.log('x'.repeat(40 * 1024)); console.error('failure'); process.exit(3);");

        var result = environment().execute(workspace(),
                new ExecutionRequest(ExecutionOperation.RUN_PROGRAM, List.of("program.mjs")));

        assertFalse(result.success());
        assertEquals(3, result.exitCode());
        assertTrue(result.summary().contains("[truncated]"));
        assertTrue(result.summary().contains("stderr:\nfailure"));
    }

    @Test
    void terminatesTimedOutProgram() throws IOException {
        write("program.mjs", "setTimeout(() => {}, 60_000);");

        var result = new LocalExecutionEnvironment(Duration.ofMillis(100)).execute(workspace(),
                new ExecutionRequest(ExecutionOperation.RUN_PROGRAM, List.of("program.mjs")));

        assertFalse(result.success());
        assertEquals(-1, result.exitCode());
        assertTrue(result.summary().startsWith("execution timed out after PT0.1S"));
    }

    @Test
    void rejectsUnsupportedOperationsAndUnsafePaths() throws IOException {
        write("program.mjs", "console.log('ok');");
        var environment = environment();

        assertThrows(UnsupportedOperationException.class,
                () -> environment.execute(workspace(), new ExecutionRequest(ExecutionOperation.FORMAT, List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> environment.execute(workspace(),
                        new ExecutionRequest(ExecutionOperation.RUN_PROGRAM, List.of("../program.mjs"))));
        assertThrows(IllegalArgumentException.class,
                () -> environment.execute(workspace(),
                        new ExecutionRequest(ExecutionOperation.COMPILE, List.of("--watch"))));
    }

    @Test
    void rejectsMissingWorkspaceRoot() {
        var missing = new Workspace(new WorkspaceReference(WorkspaceKind.LEARNING, 2), root.resolve("missing"));

        assertThrows(IllegalArgumentException.class,
                () -> environment().execute(missing,
                        new ExecutionRequest(ExecutionOperation.RUN_PROGRAM, List.of("program.mjs"))));
    }

    @Test
    void usesThePlatformPackageManagerLauncher() {
        var windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");

        assertEquals(windows ? "pnpm.cmd" : "pnpm", LocalExecutionEnvironment.packageManagerCommand());
    }

    private LocalExecutionEnvironment environment() {
        return new LocalExecutionEnvironment(Duration.ofSeconds(2));
    }

    private Workspace workspace() {
        return new Workspace(new WorkspaceReference(WorkspaceKind.LEARNING, 1), root);
    }

    private void write(String relativePath, String content) throws IOException {
        Files.writeString(root.resolve(relativePath), content, StandardCharsets.UTF_8);
    }
}
