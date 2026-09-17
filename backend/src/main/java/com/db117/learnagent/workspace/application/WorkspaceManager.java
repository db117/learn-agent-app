package com.db117.learnagent.workspace.application;

import com.db117.learnagent.config.RuntimeConfig;
import com.db117.learnagent.language.LanguagePack;
import com.db117.learnagent.language.WorkspaceTemplate;
import com.db117.learnagent.workspace.domain.*;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;

/** 管理 LearningWorkspace 和 ProjectWorkspace 的目录及文本文件。 */
@ApplicationScoped
public final class WorkspaceManager {
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private final RuntimeConfig config;

    public WorkspaceManager(RuntimeConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    public LearningWorkspace ensureLearningWorkspace(long journeyId, LanguagePack languagePack)
            throws IOException {
        if (languagePack == null) {
            throw new IllegalArgumentException("languagePack must not be null");
        }
        var workspace = learningWorkspace(journeyId);
        var templateProvider = languagePack.templates();
        if (templateProvider == null || templateProvider.templates() == null) {
            throw new IllegalArgumentException("languagePack templates must not be null");
        }
        var templatesByPath = new LinkedHashMap<Path, String>();
        for (WorkspaceTemplate template : templateProvider.templates()) {
            if (template == null) {
                throw new IllegalArgumentException("languagePack template must not be null");
            }
            var path = resolve(workspace, template.path());
            var normalizedPath = relativePath(workspace, path);
            if (templatesByPath.putIfAbsent(path, template.content()) != null) {
                throw new IllegalArgumentException("duplicate Workspace template path: " + normalizedPath);
            }
        }
        ensureRoot(workspace.root());
        for (var template : templatesByPath.entrySet()) {
            var path = template.getKey();
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Workspace template path must be a regular file: "
                        + relativePath(workspace, path));
            }
            if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                writeNew(path, template.getValue());
            }
        }
        return workspace;
    }

    public ProjectWorkspace ensureProjectWorkspace(long projectId) throws IOException {
        var workspace = projectWorkspace(projectId);
        ensureRoot(workspace.root());
        return workspace;
    }

    public LearningWorkspace learningWorkspace(long journeyId) {
        return new LearningWorkspace(journeyId, root("journeys", journeyId));
    }

    public ProjectWorkspace projectWorkspace(long projectId) {
        return new ProjectWorkspace(projectId, root("projects", projectId));
    }

    public List<WorkspaceFileEntry> listFiles(Workspace workspace) throws IOException {
        requireWorkspace(workspace);
        ensureManagedRootHasNoSymlink(workspace.root());
        try (Stream<Path> paths = Files.walk(workspace.root())) {
            return paths.filter(path -> !path.equals(workspace.root()))
                    .filter(path -> !isDependencyPath(workspace, path))
                    .filter(path -> isRegularNonSymlink(workspace, path))
                    .map(path -> entry(workspace, path))
                    .sorted(Comparator.comparing(WorkspaceFileEntry::path))
                    .toList();
        }
    }

    public WorkspaceFile readFile(Workspace workspace, String relativePath) throws IOException {
        var path = resolveFile(workspace, relativePath);
        ensureSize(path);
        return file(workspace, path, Files.readString(path, StandardCharsets.UTF_8));
    }

    public WorkspaceFile writeFile(Workspace workspace, String relativePath, String content)
            throws IOException {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        var path = resolve(workspace, relativePath);
        try {
            ensureRoot(workspace.root());
        } catch (IOException error) {
            throw new IllegalArgumentException("Workspace root is not writable", error);
        }
        ensureNoSymlinkPath(workspace.root(), path);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("path must be a regular file: " + relativePath);
        }
        Files.createDirectories(path.getParent());
        ensureNoSymlinkPath(workspace.root(), path);
        ensureSize(content.getBytes(StandardCharsets.UTF_8).length);
        writeAtomically(path, content);
        return file(workspace, path, content);
    }

    private Path root(String category, long ownerId) {
        if (ownerId <= 0) {
            throw new IllegalArgumentException("ownerId must be positive");
        }
        if (config.dataDir() == null || config.dataDir().isBlank()) {
            throw new IllegalArgumentException("dataDir must not be blank");
        }
        return Path.of(config.dataDir()).resolve(category).resolve(Long.toString(ownerId)).resolve("workspace")
                .toAbsolutePath().normalize();
    }

    private static Path resolveFile(Workspace workspace, String relativePath) throws IOException {
        var path = resolve(workspace, relativePath);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.NoSuchFileException(relativePath);
        }
        ensureSize(path);
        return path;
    }

    private static Path resolve(Workspace workspace, String relativePath) {
        requireWorkspace(workspace);
        if (relativePath == null || relativePath.isBlank() || relativePath.contains("\\")) {
            throw new IllegalArgumentException("path must be a non-empty POSIX relative path");
        }
        final Path relative;
        try {
            relative = Path.of(relativePath);
        } catch (java.nio.file.InvalidPathException error) {
            throw new IllegalArgumentException("path must be a valid POSIX relative path", error);
        }
        var containsParent = false;
        for (Path part : relative) {
            if (part.toString().equals("..")) {
                containsParent = true;
                break;
            }
        }
        if (relative.isAbsolute() || relative.getNameCount() == 0 || containsParent) {
            throw new IllegalArgumentException("path must stay inside Workspace: " + relativePath);
        }
        var path = workspace.root().resolve(relative).normalize();
        if (!path.startsWith(workspace.root().normalize())) {
            throw new IllegalArgumentException("path must stay inside Workspace: " + relativePath);
        }
        try {
            ensureManagedRootHasNoSymlink(workspace.root());
            ensureNoSymlinkPath(workspace.root(), path);
        } catch (IOException error) {
            throw new IllegalArgumentException("path must stay inside Workspace: " + relativePath, error);
        }
        return path;
    }

    private static void ensureRoot(Path root) throws IOException {
        ensureManagedRootHasNoSymlink(root);
        Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Workspace root must be a directory: " + root);
        }
    }

    private static void ensureManagedRootHasNoSymlink(Path root) throws IOException {
        var category = root.getParent() == null ? null : root.getParent().getParent();
        if (category == null) {
            throw new IllegalArgumentException("Workspace root must have a managed parent");
        }
        ensureNoSymlinkPath(category, root);
    }

    private static void requireWorkspace(Workspace workspace) {
        if (workspace == null || workspace.root() == null) {
            throw new IllegalArgumentException("workspace must not be null");
        }
    }

    private static void ensureNoSymlinkPath(Path root, Path path) throws IOException {
        var current = root;
        if (Files.isSymbolicLink(current)) {
            throw new IllegalArgumentException("Workspace root must not be a symlink");
        }
        for (Path part : root.relativize(path)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("path must not traverse a symlink: " + path);
            }
        }
    }

    private static boolean isRegularNonSymlink(Workspace workspace, Path path) {
        try {
            return !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && path.toRealPath().startsWith(workspace.root().toRealPath());
        } catch (IOException error) {
            return false;
        }
    }

    private static boolean isDependencyPath(Workspace workspace, Path path) {
        var relative = workspace.root().relativize(path);
        if (relative.getNameCount() == 0) {
            return false;
        }
        var first = relative.getName(0).toString();
        return first.equals("node_modules")
                || (workspace.reference().kind() == WorkspaceKind.LEARNING
                && relative.getNameCount() == 1
                && first.equals("pnpm-lock.yaml"));
    }

    private static WorkspaceFileEntry entry(Workspace workspace, Path path) {
        try {
            return new WorkspaceFileEntry(relativePath(workspace, path),
                    Files.size(path), Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant());
        } catch (IOException error) {
            throw new IllegalArgumentException("cannot inspect Workspace file: " + path, error);
        }
    }

    private static WorkspaceFile file(Workspace workspace, Path path, String content) throws IOException {
        return new WorkspaceFile(relativePath(workspace, path), content,
                Files.size(path), Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant());
    }

    private static void writeNew(Path path, String content) throws IOException {
        ensureSize(content.getBytes(StandardCharsets.UTF_8).length);
        Files.createDirectories(path.getParent());
        writeAtomically(path, content);
    }

    private static void writeAtomically(Path path, String content) throws IOException {
        var temporary = Files.createTempFile(path.getParent(), ".workspace-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String relativePath(Workspace workspace, Path path) {
        return workspace.root().relativize(path).toString()
                .replace(path.getFileSystem().getSeparator(), "/");
    }

    private static void ensureSize(Path path) throws IOException {
        ensureSize(Files.size(path));
    }

    private static void ensureSize(long size) {
        if (size > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("file exceeds 2 MiB");
        }
    }
}
