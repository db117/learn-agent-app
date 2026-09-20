package com.db117.learnagent.execution;

import com.db117.learnagent.config.RuntimeConfig;
import com.db117.learnagent.language.typescript.TypeScriptLanguagePack;
import com.db117.learnagent.workspace.application.WorkspaceManager;
import com.db117.learnagent.workspace.domain.LearningWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeScriptWorkspaceToolchainTest {
    @Test
    void initializesAnIndependentWorkspaceThatCompilesAndRunsVitest() throws Exception {
        var dataDir = Files.createTempDirectory("learn-agent-independent-workspace-");
        try {
            assertNoAncestorNodeModules(dataDir);
            var manager = new WorkspaceManager(new RuntimeConfig() {
                @Override
                public String dataDir() {
                    return dataDir.toString();
                }

                @Override
                public boolean memoryEnabled() {
                    return false;
                }
            });
            LearningWorkspace workspace = manager.ensureLearningWorkspace(1, new TypeScriptLanguagePack());
            assertFalse(Files.exists(workspace.root().resolve("node_modules")));
            var environment = new LocalExecutionEnvironment(Duration.ofSeconds(60));

            var compile = environment.execute(workspace,
                    new ExecutionRequest(ExecutionOperation.COMPILE, List.of()));
            assertTrue(compile.success(), compile.summary());
            assertTrue(Files.isDirectory(workspace.root().resolve("node_modules")),
                    "TypeScript dependencies must be installed inside the independent Workspace");

            var tests = environment.execute(workspace,
                    new ExecutionRequest(ExecutionOperation.RUN_TESTS, List.of()));
            assertTrue(tests.success(), tests.summary());
        } finally {
            deleteTree(dataDir);
        }
    }

    private static void assertNoAncestorNodeModules(Path root) {
        for (var current = root; current != null; current = current.getParent()) {
            assertFalse(Files.isDirectory(current.resolve("node_modules")),
                    "isolated Workspace must not have an ancestor node_modules: " + current);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
