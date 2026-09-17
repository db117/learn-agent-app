package com.db117.learnagent.practice;

import com.db117.learnagent.execution.ExecutionEnvironment;
import com.db117.learnagent.execution.ExecutionResult;
import com.db117.learnagent.execution.TypeScriptCompiler;
import com.db117.learnagent.execution.TypeScriptTestRunner;
import com.db117.learnagent.language.typescript.TypeScriptLanguagePack;
import com.db117.learnagent.practice.application.PracticeRuntimeService;
import com.db117.learnagent.practice.domain.PracticeTask;
import com.db117.learnagent.practice.domain.PracticeTaskRepository;
import com.db117.learnagent.practice.domain.VerificationPolicy;
import com.db117.learnagent.workspace.application.WorkspaceManager;
import com.db117.learnagent.workspace.domain.LearningWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PracticeRuntimeE2ETest {
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path dataDir;

    @Test
    void writesWorkspaceRunsChecksAndPersistsVerifiedEvidence() throws Exception {
        var manager = new WorkspaceManager(() -> dataDir.toString());
        LearningWorkspace workspace = manager.ensureLearningWorkspace(7, new TypeScriptLanguagePack());
        manager.writeFile(workspace, "src/index.ts", "export const answer: number = 42;");
        var repository = new InMemoryPracticeTaskRepository();
        var service = service(manager, repository);
        var task = PracticeTask.create(
                7, 1, "typescript", "code", "Answer", "Return a number", 1,
                "", new VerificationPolicy(true, true, false, false), CREATED_AT);

        var verification = service.verify(task, workspace);

        assertTrue(verification.compile().execution().success());
        assertTrue(verification.tests().passed());
        assertTrue(verification.evidence().isVerified(task.verificationPolicy()));
        assertEquals("VERIFIED", verification.task().status().name());
        assertEquals(List.of("package.json", "src/index.test.mjs", "src/index.ts", "tsconfig.json",
                "vitest.config.mjs"), verification.evidence().submittedFiles());
        assertEquals("VERIFIED", repository.saved.status().name());
    }

    private PracticeRuntimeService service(
            WorkspaceManager manager, InMemoryPracticeTaskRepository repository) {
        ExecutionEnvironment environment = (workspace, request) -> switch (request.operation()) {
            case COMPILE -> result(true, 0, "stdout:\n");
            case RUN_TESTS -> result(true, 0, "stdout:\nTests 1 passed (1)\n");
            case RUN_PROGRAM -> result(true, 0, "stdout:\nok\n");
            case FORMAT, LINT -> throw new UnsupportedOperationException();
        };
        return new PracticeRuntimeService(
                environment,
                new TypeScriptCompiler(environment),
                new TypeScriptTestRunner(environment),
                manager,
                repository);
    }

    private static ExecutionResult result(boolean success, int exitCode, String summary) {
        return new ExecutionResult(success, exitCode, summary, Duration.ZERO);
    }

    private static final class InMemoryPracticeTaskRepository implements PracticeTaskRepository {
        private PracticeTask saved;

        @Override
        public PracticeTask save(PracticeTask task) {
            saved = task.id() == null ? task.withPersistedIds(1, task.attempts()) : task;
            return saved;
        }

        @Override
        public Optional<PracticeTask> findById(long id) {
            return saved == null || saved.id() != id ? Optional.empty() : Optional.of(saved);
        }

        @Override
        public List<PracticeTask> findByLearnUnit(long journeyId, long learnUnitId) {
            return List.of();
        }
    }
}
