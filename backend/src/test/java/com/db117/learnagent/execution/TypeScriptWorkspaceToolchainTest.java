package com.db117.learnagent.execution;

import com.db117.learnagent.language.typescript.TypeScriptLanguagePack;
import com.db117.learnagent.workspace.application.WorkspaceManager;
import com.db117.learnagent.workspace.domain.LearningWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeScriptWorkspaceToolchainTest {
    @TempDir
    Path dataDir;

    @Test
    void initializesAnIndependentWorkspaceThatCompilesAndRunsVitest() throws Exception {
        var manager = new WorkspaceManager(() -> dataDir.toString());
        LearningWorkspace workspace = manager.ensureLearningWorkspace(1, new TypeScriptLanguagePack());
        var environment = new LocalExecutionEnvironment(Duration.ofSeconds(60));

        var compile = environment.execute(workspace,
                new ExecutionRequest(ExecutionOperation.COMPILE, List.of()));
        assertTrue(compile.success(), compile.summary());
        assertTrue(Files.isDirectory(workspace.root().resolve("node_modules")),
                "TypeScript dependencies must be installed inside the independent Workspace");

        var tests = environment.execute(workspace,
                new ExecutionRequest(ExecutionOperation.RUN_TESTS, List.of()));
        assertTrue(tests.success(), tests.summary());
    }
}
