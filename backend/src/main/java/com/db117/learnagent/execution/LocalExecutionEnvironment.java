package com.db117.learnagent.execution;

import com.db117.learnagent.workspace.domain.Workspace;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 在 Workspace 根目录中执行受限的本地 TypeScript 工具链操作。 */
@ApplicationScoped
public final class LocalExecutionEnvironment implements ExecutionEnvironment {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final int MAX_OUTPUT_BYTES_PER_STREAM = MAX_OUTPUT_BYTES / 2;

    private final Duration timeout;

    public LocalExecutionEnvironment() {
        this(DEFAULT_TIMEOUT);
    }

    LocalExecutionEnvironment(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @Override
    public ExecutionResult execute(Workspace workspace, ExecutionRequest request) {
        var root = validateWorkspace(workspace);
        var command = command(root, request);
        return run(root, request.operation(), command);
    }

    private List<String> command(Path root, ExecutionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        return switch (request.operation()) {
            case COMPILE -> commandWithPaths(List.of("pnpm", "exec", "tsc", "--noEmit"), root,
                    request.arguments());
            case RUN_TESTS -> commandWithPaths(List.of("pnpm", "exec", "vitest", "run"), root,
                    request.arguments());
            case RUN_PROGRAM -> commandForProgram(root, request.arguments());
            case FORMAT, LINT -> throw new UnsupportedOperationException(
                    "operation is not supported by LocalExecutionEnvironment: " + request.operation());
        };
    }

    private static List<String> commandWithPaths(List<String> fixedCommand, Path root,
                                                 List<String> arguments) {
        var command = new ArrayList<>(fixedCommand);
        for (var argument : arguments) {
            command.add(relativePath(root, argument).toString());
        }
        return command;
    }

    private static List<String> commandForProgram(Path root, List<String> arguments) {
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("RUN_PROGRAM requires a script path");
        }
        var command = new ArrayList<String>(arguments.size() + 1);
        command.add("node");
        command.add(relativePath(root, arguments.getFirst()).toString());
        command.addAll(arguments.subList(1, arguments.size()));
        return command;
    }

    private ExecutionResult run(Path root, ExecutionOperation operation, List<String> command) {
        var startedAt = System.nanoTime();
        try (ExecutorService readers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            Process process;
            try {
                var builder = new ProcessBuilder(command)
                        .directory(root.toFile())
                        .redirectErrorStream(false);
                var environment = builder.environment();
                var path = System.getenv("PATH");
                environment.clear();
                if (path != null) {
                    environment.put("PATH", path);
                }
                process = builder.start();
            } catch (IOException error) {
                return result(false, -1, "failed to start " + operation + ": " + message(error), startedAt);
            }

            try {
                process.getOutputStream().close();
            } catch (IOException error) {
                terminate(process);
                return result(false, -1, "failed to prepare " + operation + ": " + message(error), startedAt);
            }
            Future<CapturedOutput> stdout = readers.submit(() -> capture(process.getInputStream()));
            Future<CapturedOutput> stderr = readers.submit(() -> capture(process.getErrorStream()));

            boolean completed;
            try {
                completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                terminate(process);
                return result(false, -1, "execution interrupted", startedAt);
            }

            if (!completed) {
                terminate(process);
            }

            var output = output(stdout, stderr);
            var summary = formatSummary(output.stdout(), output.stderr());
            if (!completed) {
                summary = "execution timed out after " + timeout + "\n" + summary;
            }
            var exitCode = completed ? process.exitValue() : -1;
            return result(completed && exitCode == 0, exitCode, summary, startedAt);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return result(false, -1, "execution interrupted", startedAt);
        } catch (ExecutionException error) {
            return result(false, -1, "failed to read process output: " + message(error), startedAt);
        }
    }

    private static ProcessOutput output(Future<CapturedOutput> stdout, Future<CapturedOutput> stderr)
            throws InterruptedException, ExecutionException {
        return new ProcessOutput(stdout.get(), stderr.get());
    }

    private static CapturedOutput capture(InputStream input) {
        var output = new ByteArrayOutputStream(MAX_OUTPUT_BYTES_PER_STREAM);
        var buffer = new byte[8192];
        var truncated = false;
        try (input) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                var accepted = Math.min(read, MAX_OUTPUT_BYTES_PER_STREAM - output.size());
                if (accepted > 0) {
                    output.write(buffer, 0, accepted);
                }
                truncated |= accepted < read;
            }
        } catch (IOException error) {
            var suffix = "[output read failed: " + message(error) + "]";
            var bytes = suffix.getBytes(StandardCharsets.UTF_8);
            var accepted = Math.min(bytes.length, MAX_OUTPUT_BYTES_PER_STREAM - output.size());
            if (accepted > 0) {
                output.write(bytes, 0, accepted);
            }
            truncated |= accepted < bytes.length;
        }
        return new CapturedOutput(output.toString(StandardCharsets.UTF_8), truncated);
    }

    private static String formatSummary(CapturedOutput stdout, CapturedOutput stderr) {
        var summary = new StringBuilder();
        appendOutput(summary, "stdout", stdout);
        appendOutput(summary, "stderr", stderr);
        return summary.toString();
    }

    private static void appendOutput(StringBuilder summary, String name, CapturedOutput output) {
        summary.append(name).append(":\n").append(output.text());
        if (!output.text().endsWith("\n")) {
            summary.append('\n');
        }
        if (output.truncated()) {
            summary.append("[truncated]\n");
        }
    }

    private static ExecutionResult result(boolean success, int exitCode, String summary, long startedAt) {
        return new ExecutionResult(success, exitCode, summary, Duration.ofNanos(System.nanoTime() - startedAt));
    }

    private static void terminate(Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        process.destroyForcibly();
        try {
            process.waitFor();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path validateWorkspace(Workspace workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("workspace must not be null");
        }
        var root = workspace.root();
        if (root == null || Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Workspace root must be an existing non-symlink directory");
        }
        return root;
    }

    private static Path relativePath(Path root, String value) {
        if (value == null || value.isBlank() || value.contains("\\") || value.startsWith("-")) {
            throw new IllegalArgumentException("argument must be a POSIX relative Workspace path");
        }
        final Path relative;
        try {
            relative = Path.of(value);
        } catch (java.nio.file.InvalidPathException error) {
            throw new IllegalArgumentException("argument must be a valid Workspace path", error);
        }
        var containsParent = false;
        for (var part : relative) {
            if (part.toString().equals("..")) {
                containsParent = true;
                break;
            }
        }
        if (relative.isAbsolute() || relative.getNameCount() == 0 || containsParent) {
            throw new IllegalArgumentException("argument must stay inside Workspace: " + value);
        }
        var resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("argument must stay inside Workspace: " + value);
        }
        var current = root;
        for (var part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("argument must not traverse a symlink: " + value);
            }
        }
        return relative;
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /** 单个输出流的有限快照。 */
    private record CapturedOutput(
            /** 已保存的 UTF-8 文本。 */
            String text,
            /** 是否因超过 32 KiB 流上限而截断。 */
            boolean truncated) {
    }

    /** 两条输出流的读取结果，避免 stderr 写满时阻塞 stdout。 */
    private record ProcessOutput(
            /** 标准输出快照。 */
            CapturedOutput stdout,
            /** 标准错误快照。 */
            CapturedOutput stderr) {
    }
}
