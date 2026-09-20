package com.db117.learnagent.agent.tool;

import com.db117.learnagent.config.RuntimeConfig;
import com.db117.learnagent.execution.ExecutionEnvironment;
import com.db117.learnagent.execution.ExecutionResult;
import com.db117.learnagent.execution.TypeScriptCompiler;
import com.db117.learnagent.execution.TypeScriptTestRunner;
import com.db117.learnagent.practice.application.PracticeRuntimeService;
import com.db117.learnagent.practice.domain.PracticeTask;
import com.db117.learnagent.practice.domain.PracticeTaskRepository;
import com.db117.learnagent.workspace.application.WorkspaceApplicationService;
import com.db117.learnagent.workspace.application.WorkspaceManager;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TutorWorkspaceToolsTest {
    @TempDir
    Path dataDir;

    @Test
    void registersOnlyTheNarrowPracticeToolSurface() {
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
        ExecutionEnvironment environment = (workspace, request) ->
                new ExecutionResult(true, 0, "", Duration.ZERO);
        var runtime = new PracticeRuntimeService(
                environment,
                new TypeScriptCompiler(environment),
                new TypeScriptTestRunner(environment),
                manager,
                new EmptyPracticeTaskRepository());
        var access = new WorkspaceApplicationService(
                null, null, null, null, null, manager);
        var tools = new TutorWorkspaceTools(manager, access, runtime);
        var toolkit = new Toolkit();

        toolkit.registerTool(tools);

        assertEquals(
                java.util.Set.of(
                        "list_files", "read_file", "write_file", "initialize_npm_project",
                        "install_typescript", "compile_project", "compile", "run_tests", "run_program"),
                toolkit.getToolNames());
    }

    private static final class EmptyPracticeTaskRepository implements PracticeTaskRepository {
        @Override
        public PracticeTask save(PracticeTask task) {
            return task;
        }

        @Override
        public Optional<PracticeTask> findById(long id) {
            return Optional.empty();
        }

        @Override
        public List<PracticeTask> findByLearnUnit(long journeyId, long learnUnitId) {
            return List.of();
        }
    }
}
