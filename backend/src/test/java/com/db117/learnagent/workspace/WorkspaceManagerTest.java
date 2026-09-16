package com.db117.learnagent.workspace;

import com.db117.learnagent.config.RuntimeConfig;
import com.db117.learnagent.language.LanguageMetadata;
import com.db117.learnagent.language.LanguagePack;
import com.db117.learnagent.language.Toolchain;
import com.db117.learnagent.language.WorkspaceTemplate;
import com.db117.learnagent.language.WorkspaceTemplateProvider;
import com.db117.learnagent.language.typescript.TypeScriptLanguagePack;
import com.db117.learnagent.workspace.application.WorkspaceManager;
import com.db117.learnagent.workspace.domain.WorkspaceFileEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceManagerTest {
    @TempDir
    Path dataDir;

    @Test
    void createsBothKindsOfRoot() throws IOException {
        var manager = manager();

        var learning = manager.ensureLearningWorkspace(7, new TypeScriptLanguagePack());
        var project = manager.ensureProjectWorkspace(8);

        assertEquals(dataDir.resolve("journeys/7/workspace"), learning.root());
        assertEquals(dataDir.resolve("projects/8/workspace"), project.root());
        assertTrue(Files.isDirectory(learning.root()));
        assertTrue(Files.isDirectory(project.root()));
    }

    @Test
    void canReferenceRootsWithoutCreatingThem() {
        var manager = manager();

        assertEquals(dataDir.resolve("journeys/9/workspace"), manager.learningWorkspace(9).root());
        assertEquals(dataDir.resolve("projects/10/workspace"), manager.projectWorkspace(10).root());
        assertFalse(Files.exists(dataDir.resolve("journeys/9")));
        assertFalse(Files.exists(dataDir.resolve("projects/10")));
    }

    @Test
    void learningTemplatesAreIdempotentAndPreserveExistingContent() throws IOException {
        var manager = manager();
        var workspace = manager.ensureLearningWorkspace(1, new TypeScriptLanguagePack());
        var template = workspace.root().resolve("src/index.ts");
        Files.writeString(template, "changed", StandardCharsets.UTF_8);

        manager.ensureLearningWorkspace(1, new TypeScriptLanguagePack());

        assertEquals("changed", Files.readString(template));
    }

    @Test
    void listReadWriteRoundTripIncludesHiddenFilesAndSortsPosixPaths() throws IOException {
        var manager = manager();
        var workspace = manager.ensureProjectWorkspace(2);
        manager.writeFile(workspace, "z.txt", "z");
        manager.writeFile(workspace, ".env", "secret");
        manager.writeFile(workspace, "a/b.txt", "中文");

        var files = manager.listFiles(workspace);

        assertEquals(List.of(".env", "a/b.txt", "z.txt"), files.stream()
                .map(WorkspaceFileEntry::path).toList());
        var read = manager.readFile(workspace, "a/b.txt");
        assertEquals("中文", read.content());
        assertEquals("中文".getBytes(StandardCharsets.UTF_8).length, read.size());
        assertEquals("a/b.txt", read.path());
        var written = manager.writeFile(workspace, "a/b.txt", "updated");
        assertEquals("updated", written.content());
        assertEquals("updated", Files.readString(workspace.root().resolve("a/b.txt")));
    }

    @Test
    void rejectsUnsafePathsAndMissingFiles() throws IOException {
        var manager = manager();
        var workspace = manager.ensureProjectWorkspace(3);

        for (var path : List.of("", "/tmp/file", "../file", "a/../file", "a\\b")) {
            assertThrows(IllegalArgumentException.class, () -> manager.readFile(workspace, path));
        }
        assertThrows(java.nio.file.NoSuchFileException.class,
                () -> manager.readFile(workspace, "missing.txt"));
    }

    @Test
    void rejectsFilesOverTwoMiB() throws IOException {
        var manager = manager();
        var workspace = manager.ensureProjectWorkspace(4);
        var content = "x".repeat(2 * 1024 * 1024 + 1);

        assertThrows(IllegalArgumentException.class,
                () -> manager.writeFile(workspace, "large.txt", content));
    }

    @Test
    void rejectsSymlinkEscapeWhenSupported() throws IOException {
        var manager = manager();
        var workspace = manager.ensureProjectWorkspace(5);
        var outside = dataDir.resolve("outside");
        Files.writeString(outside, "outside");
        var link = workspace.root().resolve("link.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | FileSystemException ignored) {
            return;
        }

        assertFalse(manager.listFiles(workspace).stream().anyMatch(file -> file.path().equals("link.txt")));
        assertThrows(IllegalArgumentException.class, () -> manager.readFile(workspace, "link.txt"));
        assertThrows(IllegalArgumentException.class, () -> manager.writeFile(workspace, "link.txt", "nope"));
    }

    @Test
    void rejectsManagedAncestorSymlinkWhenSupported() throws IOException {
        var manager = manager();
        var outside = dataDir.resolve("outside-projects");
        Files.createDirectories(outside);
        var projects = dataDir.resolve("projects");
        try {
            Files.createSymbolicLink(projects, outside);
        } catch (UnsupportedOperationException | FileSystemException ignored) {
            return;
        }

        assertThrows(IllegalArgumentException.class, () -> manager.ensureProjectWorkspace(11));
        assertFalse(Files.exists(outside.resolve("11/workspace")));
    }

    @Test
    void rejectsDuplicateTemplatePaths() {
        var manager = manager();
        var template = new WorkspaceTemplate("src/index.ts", "export {};");
        var pack = new LanguagePack() {
            @Override
            public String id() {
                return "duplicate";
            }

            @Override
            public LanguageMetadata metadata() {
                return new LanguageMetadata("Duplicate", java.util.Set.of("ts"));
            }

            @Override
            public Toolchain toolchain() {
                return new Toolchain("node", "pnpm", "tsc", "vitest");
            }

            @Override
            public WorkspaceTemplateProvider templates() {
                return () -> List.of(template, template);
            }
        };

        assertThrows(IllegalArgumentException.class,
                () -> manager.ensureLearningWorkspace(6, pack));
        assertFalse(Files.exists(dataDir.resolve("journeys/6")));
    }

    private WorkspaceManager manager() {
        RuntimeConfig config = () -> dataDir.toString();
        return new WorkspaceManager(config);
    }
}
