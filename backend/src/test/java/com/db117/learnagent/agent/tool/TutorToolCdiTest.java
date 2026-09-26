package com.db117.learnagent.agent.tool;

import io.agentscope.core.tool.Toolkit;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(TutorToolCdiTest.IsolatedToolProfile.class)
class TutorToolCdiTest {
    @Inject
    TutorLearningTools learningTools;

    @Inject
    TutorWorkspaceTools workspaceTools;

    @Inject
    TutorPracticeTools practiceTools;

    @Inject
    TutorProgressTools progressTools;

    @Test
    void cdiToolBeansRegisterTheirAgentScopeTools() {
        Toolkit learningToolkit = new Toolkit();
        learningToolkit.registerTool(learningTools);

        Toolkit workspaceToolkit = new Toolkit();
        workspaceToolkit.registerTool(workspaceTools);

        assertEquals(java.util.Set.of("save_learning_content"), learningToolkit.getToolNames());
        Toolkit practiceToolkit = new Toolkit();
        practiceToolkit.registerTool(practiceTools);
        assertEquals(
                java.util.Set.of("save_practice_test", "verify_practice_test"),
                practiceToolkit.getToolNames());
        Toolkit progressToolkit = new Toolkit();
        progressToolkit.registerTool(progressTools);
        assertEquals(java.util.Set.of("record_practice_assessment"), progressToolkit.getToolNames());
        assertEquals(
                java.util.Set.of(
                        "list_files", "read_file", "write_file", "initialize_npm_project",
                        "install_typescript", "compile_project", "compile", "run_tests", "run_program"),
                workspaceToolkit.getToolNames());
    }

    public static final class IsolatedToolProfile implements QuarkusTestProfile {
        private static final Path DATA_DIR = Path.of(
                System.getProperty("java.io.tmpdir"), "learn-agent-tool-cdi-" + UUID.randomUUID());

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("learn-agent.data-dir", DATA_DIR.toString());
        }
    }
}
