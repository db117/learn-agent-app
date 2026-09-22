package com.db117.learnagent.practice;

import com.db117.learnagent.config.RuntimeConfig;
import com.db117.learnagent.execution.ExecutionEnvironment;
import com.db117.learnagent.execution.ExecutionResult;
import com.db117.learnagent.execution.TypeScriptCompiler;
import com.db117.learnagent.execution.TypeScriptTestRunner;
import com.db117.learnagent.learning.application.LearningRequestException;
import com.db117.learnagent.practice.application.PracticeRuntimeService;
import com.db117.learnagent.practice.domain.PracticeTask;
import com.db117.learnagent.practice.domain.PracticeTaskRepository;
import com.db117.learnagent.practice.domain.VerificationPolicy;
import com.db117.learnagent.workspace.application.WorkspaceManager;
import com.db117.learnagent.workspace.domain.Workspace;
import com.db117.learnagent.workspace.domain.WorkspaceKind;
import com.db117.learnagent.workspace.domain.WorkspaceReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PracticeRuntimeServiceTest {
    @TempDir
    Path dataDir;

    @Test
    void rejectsUnsupportedChecksBeforeExecutionAndEvidencePersistence() {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger saves = new AtomicInteger();
        ExecutionEnvironment environment = (workspace, request) -> {
            executions.incrementAndGet();
            return new ExecutionResult(true, 0, "", Duration.ZERO);
        };
        PracticeRuntimeServiceTest.RecordingPracticeTaskRepository repository = new RecordingPracticeTaskRepository(saves);
        PracticeRuntimeService service = service(environment, repository);
        PracticeTask task = task(new VerificationPolicy(true, true, true, true));

        LearningRequestException error = assertThrows(LearningRequestException.class, () -> service.verify(task, null));

        assertEquals("UNSUPPORTED_VERIFICATION_POLICY", error.code());
        assertTrue(error.publicMessage().contains("lint"));
        assertTrue(error.publicMessage().contains("runtime"));
        assertEquals(0, executions.get());
        assertEquals(0, saves.get());
    }

    @Test
    void keepsCompileAndTestsOnlyPolicyOnTheExistingVerificationPath() {
        ExecutionEnvironment environment = (workspace, request) ->
                new ExecutionResult(true, 0, "Tests  1 passed (1)", Duration.ZERO);
        PracticeRuntimeServiceTest.RecordingPracticeTaskRepository repository = new RecordingPracticeTaskRepository(new AtomicInteger());
        PracticeRuntimeService service = service(environment, repository);
        PracticeTask task = task(new VerificationPolicy(true, true, false, false));
        Workspace workspace = new Workspace(
                new WorkspaceReference(WorkspaceKind.LEARNING, 1), dataDir);

        PracticeRuntimeService.PracticeVerification result = service.verify(task, workspace);

        assertTrue(result.evidence().isVerified(task.verificationPolicy()));
        assertSame(result.task(), repository.saved);
    }

    private PracticeTask task(VerificationPolicy policy) {
        return PracticeTask.create(
                1,
                2,
                "typescript",
                "CODE",
                "Practice",
                "Practice task",
                1,
                "",
                policy,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private PracticeRuntimeService service(
            ExecutionEnvironment environment,
            PracticeTaskRepository repository) {
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
        WorkspaceManager manager = new WorkspaceManager(config);
        return new PracticeRuntimeService(
                environment,
                new TypeScriptCompiler(environment),
                new TypeScriptTestRunner(environment),
                manager,
                repository);
    }

    private static final class RecordingPracticeTaskRepository implements PracticeTaskRepository {
        private final AtomicInteger saves;
        private PracticeTask saved;

        private RecordingPracticeTaskRepository(AtomicInteger saves) {
            this.saves = saves;
        }

        @Override
        public PracticeTask save(PracticeTask task) {
            saves.incrementAndGet();
            saved = task;
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
