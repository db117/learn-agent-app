package com.db117.learnagent.agent.tool;

import com.db117.learnagent.agent.api.TutorSessionMode;
import com.db117.learnagent.agent.application.TutorContext;
import com.db117.learnagent.agent.domain.WorkspaceBinding;
import com.db117.learnagent.learning.application.JourneyApplicationService;
import com.db117.learnagent.learning.domain.Chapter;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.practice.domain.PracticeAttempt;
import com.db117.learnagent.practice.domain.PracticeEvidence;
import com.db117.learnagent.practice.domain.PracticeTask;
import com.db117.learnagent.practice.domain.PracticeTaskRepository;
import com.db117.learnagent.practice.domain.RuntimeResult;
import com.db117.learnagent.practice.domain.VerificationPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TutorPracticeToolsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void savesRedactedQuestionAndRecordsChoiceEvidence() throws Exception {
        LearningJourney journey = learningJourney();
        LearningJourney updated = journey.recordPracticeVerified("variables", Instant.parse("2026-01-01T00:01:00Z"));
        TutorPracticeToolsTest.StubJourneys journeys = new StubJourneys(journey, updated);
        TutorPracticeToolsTest.InMemoryPracticeTasks tasks = new InMemoryPracticeTasks(verifiedCodeTask());
        TutorPracticeTools tools = new TutorPracticeTools(journeys, tasks);
        TutorContext context = context(journey);

        com.fasterxml.jackson.databind.JsonNode saved = JSON.readTree(tools.savePracticeTest(context, """
                {"prompt":"哪一项声明了变量类型？","options":[
                  {"id":"a","label":"const answer: number = 1"},
                  {"id":"b","label":"const answer = 1"},
                  {"id":"c","label":"let answer"},
                  {"id":"d","label":"var answer"}],
                  "correctOptionId":"a"}
                """));

        assertTrue(saved.get("taskId").isNumber());
        assertTrue(saved.has("prompt"));
        assertFalse(saved.has("correctOptionId"));
        assertEquals(4, saved.get("options").size());

        com.fasterxml.jackson.databind.JsonNode wrong = JSON.readTree(tools.verifyPracticeTest(context, saved.get("taskId").asLong(), "b"));
        assertFalse(wrong.get("verified").asBoolean());
        assertFalse(wrong.get("choiceCorrect").asBoolean());
        assertEquals(1, tasks.task().attempts().size());

        com.fasterxml.jackson.databind.JsonNode correct = JSON.readTree(tools.verifyPracticeTest(context, saved.get("taskId").asLong(), "a"));
        assertTrue(correct.get("verified").asBoolean());
        assertTrue(correct.get("choiceCorrect").asBoolean());
        assertEquals("VERIFIED", tasks.task().status().name());
        assertEquals("COMPLETED", journeys.current().status().name());
    }

    private static TutorContext context(LearningJourney journey) {
        LearnUnit unit = journey.learnUnit("variables");
        return new TutorContext(
                1L,
                "学习者",
                "TypeScript 初学者",
                7L,
                journey.title(),
                "掌握 TypeScript 基础",
                journey.languagePackId(),
                unit.code(),
                unit.title(),
                unit.objective(),
                unit.content(),
                journey.currentItem().status(),
                false,
                0,
                1,
                TutorSessionMode.LEARNING,
                WorkspaceBinding.learning(7L));
    }

    private static LearningJourney learningJourney() {
        Chapter chapter = Chapter.create("basics", "基础", 0);
        LearnUnit unit = LearnUnit.create(
                "variables",
                "变量与类型",
                "能够声明变量并理解基本类型",
                "## Concept\n变量保存值。\n## Practice\n声明一个带类型的变量。",
                0,
                chapter.code(),
                Set.of()).withId(1L);
        LearningJourney created = LearningJourney.create(
                1L,
                "typescript",
                "TypeScript 基础",
                List.of(chapter),
                List.of(unit),
                Instant.parse("2026-01-01T00:00:00Z"));
        return created.withPersistedIds(1L, created.chapters(), List.of(unit), created.pathItems());
    }

    private static PracticeTask verifiedCodeTask() {
        PracticeTask task = PracticeTask.create(
                1L,
                1L,
                "typescript",
                "CODE",
                "代码练习",
                "完成代码练习",
                1,
                "",
                new VerificationPolicy(true, true, false, false),
                Instant.parse("2026-01-01T00:00:00Z"));
        PracticeEvidence evidence = new PracticeEvidence(
                true,
                true,
                1,
                false,
                RuntimeResult.NOT_RUN,
                List.of("src/index.ts"),
                Instant.parse("2026-01-01T00:00:30Z"));
        PracticeTask verified = task.recordAttempt(PracticeAttempt.submit(
                evidence, Instant.parse("2026-01-01T00:00:30Z")));
        return verified.withPersistedIds(99L, verified.attempts());
    }

    private static final class StubJourneys extends JourneyApplicationService {
        private LearningJourney current;
        private final LearningJourney completed;

        private StubJourneys(LearningJourney current, LearningJourney completed) {
            super(null, null, null);
            this.current = current;
            this.completed = completed;
        }

        @Override
        public LearningJourney learningJourneyFor(long journeyId) {
            return current;
        }

        @Override
        public LearningJourney recordPracticeVerified(long journeyId, String learnUnitCode) {
            current = completed;
            return current;
        }

        private LearningJourney current() {
            return current;
        }
    }

    private static final class InMemoryPracticeTasks implements PracticeTaskRepository {
        private final List<PracticeTask> tasks = new ArrayList<>();

        private InMemoryPracticeTasks(PracticeTask initial) {
            tasks.add(initial);
        }

        @Override
        public PracticeTask save(PracticeTask candidate) {
            PracticeTask persisted = candidate.id() == null
                    ? candidate.withPersistedIds(tasks.size() + 1L, candidate.attempts())
                    : candidate;
            tasks.removeIf(value -> value.id().equals(persisted.id()));
            tasks.add(persisted);
            return persisted;
        }

        @Override
        public Optional<PracticeTask> findById(long id) {
            return tasks.stream().filter(value -> value.id() == id).findFirst();
        }

        @Override
        public List<PracticeTask> findByLearnUnit(long journeyId, long learnUnitId) {
            return tasks.stream()
                    .filter(value -> value.journeyId() == journeyId && value.learnUnitId() == learnUnitId)
                    .toList();
        }

        private PracticeTask task() {
            return tasks.stream().filter(value -> "CHOICE".equals(value.type())).findFirst().orElseThrow();
        }
    }
}
