package com.db117.learnagent.agent.tool;

import com.db117.learnagent.agent.api.TutorSessionMode;
import com.db117.learnagent.agent.application.TutorContext;
import com.db117.learnagent.agent.domain.WorkspaceBinding;
import com.db117.learnagent.config.RuntimeConfig;
import com.db117.learnagent.learning.application.JourneyApplicationService;
import com.db117.learnagent.learning.application.LearningRequestException;
import com.db117.learnagent.learning.domain.Chapter;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningPathItem;
import com.db117.learnagent.practice.domain.*;
import com.db117.learnagent.workspace.application.WorkspaceManager;
import com.db117.learnagent.workspace.domain.LearningWorkspace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TutorProgressToolsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dataDir;

    @Test
    void storesAnAssessmentCandidateWithoutProgressAndRejectsChangedCode() throws Exception {
        LearningJourney journey = learningJourney();
        WorkspaceManager workspaces = new WorkspaceManager(config());
        LearningWorkspace workspace = workspaces.learningWorkspace(7L);
        workspaces.writeFile(workspace, "src/index.ts", "export const answer = 42;");
        String digest = workspaces.contentDigest(workspace);
        PracticeEvidence evidence = new PracticeEvidence(
                true, true, 1, false, RuntimeResult.NOT_RUN, List.of("src/index.ts"),
                Instant.parse("2026-01-01T00:00:30Z"), false, digest);
        PracticeAttempt attempt = PracticeAttempt.submit(evidence, evidence.verifiedAt()).withId(31L);
        PracticeTask task = PracticeTask.create(
                        1L, 1L, "typescript", "CODE", "代码练习", "理解变量", 1, "",
                        new VerificationPolicy(true, true, false, false), evidence.verifiedAt())
                .recordAttempt(PracticeAttempt.submit(evidence, evidence.verifiedAt()))
                .withPersistedIds(21L, List.of(attempt));
        InMemoryPracticeTasks practiceTasks = new InMemoryPracticeTasks(task);
        InMemoryAssessments assessments = new InMemoryAssessments();
        TutorProgressTools tools = new TutorProgressTools(
                new StubJourneys(journey), practiceTasks, assessments, workspaces);

        JsonNode result = JSON.readTree(tools.recordPracticeAssessment(
                context(journey), attempt.id(), "READY", "能解释类型标注的作用，因此我判断已经理解。"));

        assertTrue(result.get("assessmentId").isNumber());
        assertEquals(PracticeAssessmentVerdict.READY, assessments.latest().verdict());
        assertEquals("ACTIVE", journey.status().name());
        assertEquals("variables", journey.currentItem().learnUnitCode());

        workspaces.writeFile(workspace, "src/index.ts", "export const answer = 43;");
        LearningRequestException stale = assertThrows(LearningRequestException.class,
                () -> tools.recordPracticeAssessment(
                        context(journey), attempt.id(), "READY", "重试旧提交"));
        assertEquals("PRACTICE_CHECK_STALE", stale.code());
        assertEquals(1, assessments.size());
    }

    private RuntimeConfig config() {
        return new RuntimeConfig() {
            @Override
            public String dataDir() {
                return dataDir.toString();
            }

            @Override
            public boolean memoryEnabled() {
                return false;
            }
        };
    }

    private static TutorContext context(LearningJourney journey) {
        LearnUnit unit = journey.learnUnit("variables");
        return new TutorContext(
                1L, "学习者", "TypeScript 初学者", 7L, journey.title(), "掌握 TypeScript 基础",
                journey.languagePackId(), unit.code(), unit.title(), unit.objective(), unit.content(),
                journey.currentItem().status(), false, 0, 1, TutorSessionMode.LEARNING,
                WorkspaceBinding.learning(7L));
    }

    private static LearningJourney learningJourney() {
        Chapter chapter = Chapter.create("basics", "基础", 0);
        LearnUnit unit = LearnUnit.create("variables", "变量", "理解变量", "", 0, chapter.code(), Set.of())
                .withId(1L);
        LearningJourney created = LearningJourney.create(1L, "typescript", "TypeScript 基础",
                List.of(chapter), List.of(unit), Instant.parse("2026-01-01T00:00:00Z"));
        List<LearningPathItem> items = created.pathItems().stream()
                .map(item -> item.withId(1L))
                .toList();
        return created.withPersistedIds(1L, List.of(chapter.withId(1L)), List.of(unit), items);
    }

    private static final class StubJourneys extends JourneyApplicationService {
        private final LearningJourney journey;

        private StubJourneys(LearningJourney journey) {
            super(null, null, null);
            this.journey = journey;
        }

        @Override
        public LearningJourney learningJourneyFor(long journeyId) {
            return journey;
        }
    }

    private static final class InMemoryPracticeTasks implements PracticeTaskRepository {
        private final PracticeTask task;

        private InMemoryPracticeTasks(PracticeTask task) {
            this.task = task;
        }

        @Override
        public PracticeTask save(PracticeTask candidate) {
            return candidate;
        }

        @Override
        public Optional<PracticeTask> findById(long id) {
            return id == task.id() ? Optional.of(task) : Optional.empty();
        }

        @Override
        public List<PracticeTask> findByLearnUnit(long journeyId, long learnUnitId) {
            return journeyId == task.journeyId() && learnUnitId == task.learnUnitId()
                    ? List.of(task)
                    : List.of();
        }
    }

    private static final class InMemoryAssessments implements PracticeAssessmentRepository {
        private final List<PracticeAssessment> saved = new ArrayList<PracticeAssessment>();

        @Override
        public PracticeAssessment save(PracticeAssessment assessment) {
            PracticeAssessment persisted = assessment.withId(saved.size() + 1L);
            saved.add(persisted);
            return persisted;
        }

        @Override
        public Optional<PracticeAssessment> findById(long id) {
            return saved.stream().filter(value -> value.id() == id).findFirst();
        }

        @Override
        public Optional<PracticeAssessment> findLatest(long journeyId, long learnUnitId) {
            return saved.stream()
                    .filter(value -> value.journeyId() == journeyId && value.learnUnitId() == learnUnitId)
                    .reduce((left, right) -> right);
        }

        private PracticeAssessment latest() {
            return saved.getLast();
        }

        private int size() {
            return saved.size();
        }
    }
}
