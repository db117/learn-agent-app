package com.db117.learnagent.workspace.api;

import com.db117.learnagent.config.RuntimeConfig;
import com.db117.learnagent.workspace.application.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceResourceTest {
    @TempDir
    Path dataDir;

    @Test
    void listsReadsAndWritesProjectFiles() throws IOException {
        var resource = resource();
        resource.writeProjectFile(1, "src/index.ts", new WorkspaceContentRequest("中文"));

        assertEquals("src/index.ts", resource.listProjectFiles(1).getFirst().path());
        var file = resource.readProjectFile(1, "src/index.ts");
        assertEquals("中文", file.content());
        assertEquals("中文".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, file.size());
    }

    @Test
    void mapsUnsafeAndMissingFileRequests() throws IOException {
        var resource = resource();
        resource.writeProjectFile(2, ".env", new WorkspaceContentRequest("x"));

        assertThrows(WorkspaceRequestException.class,
                () -> resource.readProjectFile(2, "../outside"));
        var error = assertThrows(WorkspaceRequestException.class,
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
