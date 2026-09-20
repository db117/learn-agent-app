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

    @Test
    void completesTheNestedNpmTypeScriptRuntimeExercise() throws Exception {
        var dataDir = Files.createTempDirectory("learn-agent-ts-runtime-");
        try {
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
            var workspace = manager.learningWorkspace(1);
            Files.createDirectories(workspace.root());
            var environment = new LocalExecutionEnvironment(Duration.ofMinutes(3));

            var initialized = environment.execute(workspace,
                    new ExecutionRequest(ExecutionOperation.INITIALIZE_NPM_PROJECT,
                            List.of("ts-runtime-practice")));
            assertTrue(initialized.success(), initialized.summary());
            manager.writeFile(workspace, "ts-runtime-practice/package.json", """
                    {
                      "name": "ts-runtime-practice",
                      "private": true,
                      "type": "module"
                    }
                    """);
            var installed = environment.execute(workspace,
                    new ExecutionRequest(ExecutionOperation.INSTALL_TYPESCRIPT,
                            List.of("ts-runtime-practice")));
            assertTrue(installed.success(), installed.summary());
            manager.writeFile(workspace, "ts-runtime-practice/tsconfig.json", """
                    {
                      "compilerOptions": {
                        "target": "ES2022",
                        "module": "NodeNext",
                        "moduleResolution": "NodeNext",
                        "rootDir": "src",
                        "outDir": "dist",
                        "strict": true
                      },
                      "include": ["src/**/*.ts"]
                    }
                    """);
            manager.writeFile(workspace, "ts-runtime-practice/src/index.ts", """
                    const studentName: string = "Ada";
                    const score: number = 95;
                    const passed: boolean = score >= 60;
                    console.log(`${studentName}: score=${score}, passed=${passed}`);
                    """);

            var compiled = environment.execute(workspace,
                    new ExecutionRequest(ExecutionOperation.COMPILE_PROJECT,
                            List.of("ts-runtime-practice")));
            assertTrue(compiled.success(), compiled.summary());
            assertTrue(Files.isRegularFile(workspace.root().resolve("ts-runtime-practice/dist/index.js")));
            var ran = environment.execute(workspace,
                    new ExecutionRequest(ExecutionOperation.RUN_PROGRAM,
                            List.of("ts-runtime-practice/dist/index.js")));
            assertTrue(ran.success(), ran.summary());
            assertTrue(ran.summary().contains("Ada: score=95, passed=true"), ran.summary());

            manager.writeFile(workspace, "ts-runtime-practice/src/index.ts", """
                    const studentName: string = "Ada";
                    const score: number = "broken";
                    const passed: boolean = score >= 60;
                    console.log(`${studentName}: score=${score}, passed=${passed}`);
                    """);
            var broken = environment.execute(workspace,
                    new ExecutionRequest(ExecutionOperation.COMPILE_PROJECT,
                            List.of("ts-runtime-practice")));
            assertFalse(broken.success(), broken.summary());
            assertTrue(broken.summary().contains("TS2322"), broken.summary());

            manager.writeFile(workspace, "ts-runtime-practice/src/index.ts", """
                    const studentName: string = "Ada";
                    const score: number = 95;
                    const passed: boolean = score >= 60;
                    console.log(`${studentName}: score=${score}, passed=${passed}`);
                    """);
            var repaired = environment.execute(workspace,
                    new ExecutionRequest(ExecutionOperation.COMPILE_PROJECT,
                            List.of("ts-runtime-practice")));
            assertTrue(repaired.success(), repaired.summary());
            var reran = environment.execute(workspace,
                    new ExecutionRequest(ExecutionOperation.RUN_PROGRAM,
                            List.of("ts-runtime-practice/dist/index.js")));
            assertTrue(reran.success(), reran.summary());
            assertTrue(reran.summary().contains("Ada: score=95, passed=true"), reran.summary());
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
