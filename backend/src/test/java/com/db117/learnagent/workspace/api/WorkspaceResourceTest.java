package com.db117.learnagent.workspace.api;

import com.db117.learnagent.config.RuntimeConfig;
import com.db117.learnagent.workspace.application.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceResourceTest {
    @TempDir
    Path dataDir;

    @Test
    void listsReadsAndWritesProjectFiles() throws IOException {
        WorkspaceResource resource = resource();
        resource.writeProjectFile(1, "src/index.ts", new WorkspaceContentRequest("中文"));

        assertEquals("src/index.ts", resource.listProjectFiles(1).getFirst().path());
        WorkspaceFileResponse file = resource.readProjectFile(1, "src/index.ts");
        assertEquals("中文", file.content());
        assertEquals("中文".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, file.size());
    }

    @Test
    void editorFileListUsesWorkspaceGitignoreRules() throws IOException {
        WorkspaceResource resource = resource();
        resource.writeProjectFile(3, ".gitignore", new WorkspaceContentRequest("*.tmp\n!keep.tmp\n"));
        resource.writeProjectFile(3, "hidden.tmp", new WorkspaceContentRequest("ignored"));
        resource.writeProjectFile(3, "keep.tmp", new WorkspaceContentRequest("visible"));
        resource.writeProjectFile(3, "src/index.ts", new WorkspaceContentRequest("visible"));

        List<String> paths = resource.listProjectFiles(3).stream()
                .map(WorkspaceFileEntryResponse::path)
                .toList();

        assertTrue(paths.contains(".gitignore"));
        assertTrue(paths.contains("keep.tmp"));
        assertTrue(paths.contains("src/index.ts"));
        assertFalse(paths.contains("hidden.tmp"));
    }

    @Test
    void exposesTheConfiguredLearningWorkspacePathForTheLocalIde() {
        WorkspaceResource resource = resource();

        assertEquals(dataDir.resolve("journeys/1/workspace").toAbsolutePath().normalize().toString(),
                resource.learningWorkspacePath(1));
    }

    @Test
    void mapsUnsafeAndMissingFileRequests() throws IOException {
        WorkspaceResource resource = resource();
        resource.writeProjectFile(2, ".env", new WorkspaceContentRequest("x"));

        assertThrows(WorkspaceRequestException.class,
                () -> resource.readProjectFile(2, "../outside"));
        WorkspaceRequestException error = assertThrows(WorkspaceRequestException.class,
                () -> resource.readProjectFile(2, "missing.txt"));
        assertEquals(404, error.status());
    }

    private WorkspaceResource resource() {
        RuntimeConfig config = new RuntimeConfig() {
            @Override
            public String dataDir() {
                return dataDir.toString();
            }

            @Override
            public boolean memoryEnabled() {
                return false;
            }
        };
        return new WorkspaceResource(new WorkspaceManager(config));
    }
}
