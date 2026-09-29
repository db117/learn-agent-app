package com.db117.learnagent.workspace.application;

import com.db117.learnagent.workspace.domain.WorkspaceFileEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 按 Workspace 自己的 .gitignore 过滤文件列表，供编辑器和迁移包共用。 */
public final class WorkspaceIgnoreRules {
    private static final String IGNORE_FILE_NAME = ".gitignore";
    private static final long MAX_IGNORE_FILE_BYTES = 2L * 1024 * 1024;
    private final Path workspaceRoot;
    private final Path repositoryRoot;
    private final Map<Path, List<IgnorePattern>> patternsByDirectory;

    private WorkspaceIgnoreRules(
            Path workspaceRoot,
            Path repositoryRoot,
            Map<Path, List<IgnorePattern>> patternsByDirectory) {
        this.workspaceRoot = workspaceRoot;
        this.repositoryRoot = repositoryRoot;
        this.patternsByDirectory = patternsByDirectory;
    }

    public static List<WorkspaceFileEntry> filter(
            Path workspaceRoot, List<WorkspaceFileEntry> workspaceFiles) throws IOException {
        WorkspaceIgnoreRules rules = read(workspaceRoot, workspaceFiles);
        ArrayList<WorkspaceFileEntry> filtered = new ArrayList<WorkspaceFileEntry>();
        for (WorkspaceFileEntry entry : workspaceFiles) {
            if (!rules.isIgnored(entry.path())) {
                filtered.add(entry);
            }
        }
        return List.copyOf(filtered);
    }

    private static WorkspaceIgnoreRules read(Path workspaceRoot, List<WorkspaceFileEntry> workspaceFiles)
            throws IOException {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        Path repositoryRoot = findRepositoryRoot(root);
        LinkedHashMap<Path, List<IgnorePattern>> patternsByDirectory =
                new LinkedHashMap<Path, List<IgnorePattern>>();

        ArrayList<Path> ancestorDirectories = new ArrayList<Path>();
        for (Path directory = root; directory != null; directory = directory.getParent()) {
            ancestorDirectories.add(directory);
            if (directory.equals(repositoryRoot)) {
                break;
            }
        }
        for (int index = ancestorDirectories.size() - 1; index >= 0; index--) {
            Path directory = ancestorDirectories.get(index);
            patternsByDirectory.put(directory, readIgnoreFile(directory.resolve(IGNORE_FILE_NAME)));
        }

        for (WorkspaceFileEntry entry : workspaceFiles) {
            if (!isIgnoreFile(entry.path())) {
                continue;
            }
            Path ignoreFile = root.resolve(entry.path()).normalize();
            Path directory = ignoreFile.getParent();
            if (directory != null && directory.startsWith(root)) {
                patternsByDirectory.putIfAbsent(directory, readIgnoreFile(ignoreFile));
            }
        }
        return new WorkspaceIgnoreRules(root, repositoryRoot, patternsByDirectory);
    }

    private boolean isIgnored(String workspacePath) {
        Path target = workspaceRoot.resolve(workspacePath).toAbsolutePath().normalize();
        if (!target.startsWith(repositoryRoot)) {
            throw new IllegalArgumentException("Workspace path escaped its managed root");
        }
        Path relative = repositoryRoot.relativize(target);
        Path current = repositoryRoot;
        for (int index = 0; index < relative.getNameCount(); index++) {
            current = current.resolve(relative.getName(index));
            boolean directory = index < relative.getNameCount() - 1;
            if (isIgnoredByApplicableRules(current, directory)) {
                return true;
            }
        }
        return false;
    }

    private boolean isIgnoredByApplicableRules(Path target, boolean directory) {
        Path ruleRoot = findRepositoryRootFor(target);
        Path parent = target.getParent();
        if (parent == null || !parent.startsWith(ruleRoot)) {
            return false;
        }
        boolean ignored = false;
        Path scope = ruleRoot;
        ignored = applyRules(scope, target, directory, ignored);
        for (Path part : ruleRoot.relativize(parent)) {
            scope = scope.resolve(part);
            ignored = applyRules(scope, target, directory, ignored);
        }
        return ignored;
    }

    private Path findRepositoryRootFor(Path target) {
        for (Path directory = target.getParent();
             directory != null && directory.startsWith(repositoryRoot);
             directory = directory.getParent()) {
            if (Files.exists(directory.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                return directory;
            }
            if (directory.equals(repositoryRoot)) {
                break;
            }
        }
        return repositoryRoot;
    }

    private boolean applyRules(Path scope, Path target, boolean directory, boolean ignored) {
        List<IgnorePattern> patterns = patternsByDirectory.get(scope);
        if (patterns == null || patterns.isEmpty()) {
            return ignored;
        }
        String relativePath = unixPath(scope.relativize(target));
        for (IgnorePattern pattern : patterns) {
            if (pattern.matches(relativePath, directory)) {
                ignored = !pattern.negated();
            }
        }
        return ignored;
    }

    private static Path findRepositoryRoot(Path workspaceRoot) {
        for (Path directory = workspaceRoot; directory != null; directory = directory.getParent()) {
            if (Files.exists(directory.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                return directory;
            }
        }
        return workspaceRoot;
    }

    private static List<IgnorePattern> readIgnoreFile(Path ignoreFile) throws IOException {
        if (!Files.isRegularFile(ignoreFile, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(ignoreFile)) {
            return List.of();
        }
        if (Files.size(ignoreFile) > MAX_IGNORE_FILE_BYTES) {
            throw new IOException(".gitignore 超过单文件允许大小: " + ignoreFile);
        }
        ArrayList<IgnorePattern> patterns = new ArrayList<IgnorePattern>();
        List<String> lines = Files.readAllLines(ignoreFile, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            IgnorePattern pattern;
            try {
                pattern = IgnorePattern.parse(lines.get(index));
            } catch (PatternSyntaxException error) {
                throw new IOException("无法解析 .gitignore 模式: " + ignoreFile + ":" + (index + 1), error);
            }
            if (pattern != null) {
                patterns.add(pattern);
            }
        }
        return List.copyOf(patterns);
    }

    private static boolean isIgnoreFile(String path) {
        return IGNORE_FILE_NAME.equals(path) || path.endsWith("/" + IGNORE_FILE_NAME);
    }

    private static String unixPath(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    /**
     * .gitignore 中的一条有序匹配规则。
     *
     * @param negated 是否通过 ! 规则重新包含匹配路径
     * @param directoryOnly 是否只匹配目录
     * @param basenameOnly 是否按任意路径层级中的单个名称匹配
     * @param glob 已编译的路径通配规则
     */
    private record IgnorePattern(boolean negated, boolean directoryOnly, boolean basenameOnly, Pattern glob) {
        private static IgnorePattern parse(String rawLine) {
            String line = trimTrailingUnescapedSpaces(rawLine);
            if (line.isEmpty() || (line.charAt(0) == '#' && !isEscaped(line, 0))) {
                return null;
            }
            boolean negated = line.charAt(0) == '!' && !isEscaped(line, 0);
            if (negated) {
                line = line.substring(1);
            }
            if (line.isEmpty()) {
                return null;
            }
            int last = line.length() - 1;
            if (line.charAt(last) == '\\' && !isEscaped(line, last)) {
                return null;
            }

            boolean directoryOnly = line.charAt(line.length() - 1) == '/'
                    && !isEscaped(line, line.length() - 1);
            if (directoryOnly) {
                line = line.substring(0, line.length() - 1);
            }
            boolean anchored = line.charAt(0) == '/' && !isEscaped(line, 0);
            if (anchored) {
                line = line.substring(1);
            }
            if (line.isEmpty()) {
                return null;
            }
            boolean basenameOnly = !anchored && line.indexOf('/') < 0;
            if (anchored && line.equals("**")) {
                return new IgnorePattern(negated, directoryOnly, false, Pattern.compile("^.*$"));
            }
            return new IgnorePattern(negated, directoryOnly, basenameOnly,
                    Pattern.compile(globToRegex(line)));
        }

        private boolean matches(String path, boolean directory) {
            if (directoryOnly && !directory) {
                return false;
            }
            if (!basenameOnly) {
                return glob.matcher(path).matches();
            }
            for (String component : path.split("/")) {
                if (glob.matcher(component).matches()) {
                    return true;
                }
            }
            return false;
        }

        private static String trimTrailingUnescapedSpaces(String line) {
            int end = line.length();
            while (end > 0 && line.charAt(end - 1) == ' ') {
                int backslashes = 0;
                for (int index = end - 2; index >= 0 && line.charAt(index) == '\\'; index--) {
                    backslashes++;
                }
                if (backslashes % 2 != 0) {
                    break;
                }
                end--;
            }
            return line.substring(0, end);
        }

        private static boolean isEscaped(String value, int index) {
            int backslashes = 0;
            for (int cursor = index - 1; cursor >= 0 && value.charAt(cursor) == '\\'; cursor--) {
                backslashes++;
            }
            return backslashes % 2 != 0;
        }

        private static String globToRegex(String glob) {
            StringBuilder regex = new StringBuilder("^");
            for (int index = 0; index < glob.length(); index++) {
                char current = glob.charAt(index);
                if (current == '\\') {
                    if (index + 1 < glob.length()) {
                        appendLiteral(regex, glob.charAt(++index));
                    } else {
                        appendLiteral(regex, current);
                    }
                } else if (current == '*') {
                    if (index + 1 < glob.length() && glob.charAt(index + 1) == '*') {
                        boolean followedBySlash = index + 2 < glob.length() && glob.charAt(index + 2) == '/';
                        boolean precededBySlash = index > 0 && glob.charAt(index - 1) == '/';
                        if ((index == 0 || precededBySlash) && followedBySlash) {
                            regex.append("(?:.*/)?");
                            index += 2;
                        } else if (precededBySlash && index + 2 == glob.length()) {
                            regex.append(".*");
                            index++;
                        } else {
                            regex.append("[^/]*[^/]*");
                            index++;
                        }
                    } else {
                        regex.append("[^/]*");
                    }
                } else if (current == '?') {
                    regex.append("[^/]");
                } else if (current == '[') {
                    int end = characterClassEnd(glob, index);
                    if (end < 0) {
                        appendLiteral(regex, current);
                    } else {
                        appendCharacterClass(regex, glob.substring(index + 1, end));
                        index = end;
                    }
                } else {
                    appendLiteral(regex, current);
                }
            }
            return regex.append('$').toString();
        }

        private static int characterClassEnd(String glob, int start) {
            int index = start + 1;
            if (index < glob.length() && (glob.charAt(index) == '!' || glob.charAt(index) == '^')) {
                index++;
            }
            if (index < glob.length() && glob.charAt(index) == ']') {
                index++;
            }
            for (; index < glob.length(); index++) {
                if (glob.charAt(index) == '\\') {
                    index++;
                } else if (glob.charAt(index) == ']') {
                    return index;
                }
            }
            return -1;
        }

        private static void appendCharacterClass(StringBuilder regex, String value) {
            regex.append('[');
            int index = 0;
            if (!value.isEmpty() && value.charAt(0) == '!') {
                regex.append('^');
                index++;
            } else if (!value.isEmpty() && value.charAt(0) == '^') {
                regex.append("\\^");
                index++;
            }
            boolean firstCharacter = index == 0 || (index == 1 && value.charAt(0) == '!');
            for (; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current == '\\' && index + 1 < value.length()) {
                    regex.append('\\').append(value.charAt(++index));
                } else if (current == ']' && firstCharacter) {
                    regex.append("\\]");
                } else {
                    regex.append(current);
                }
                firstCharacter = false;
            }
            regex.append(']');
        }

        private static void appendLiteral(StringBuilder regex, char value) {
            if ("\\.^$|?*+()[]{}".indexOf(value) >= 0) {
                regex.append('\\');
            }
            regex.append(value);
        }
    }
}
