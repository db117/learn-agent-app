package com.db117.learnagent.agent.tool;

import com.db117.learnagent.agent.api.TutorSessionMode;
import com.db117.learnagent.agent.application.TutorContext;
import com.db117.learnagent.agent.domain.WorkspaceBinding;
import com.db117.learnagent.learning.application.JourneyApplicationService;
import com.db117.learnagent.learning.domain.Chapter;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.practice.domain.PracticeTask;
import com.db117.learnagent.practice.domain.PracticeTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
        var journey = learningJourney();
        var updated = journey.recordPracticeVerified("variables", Instant.parse("2026-01-01T00:01:00Z"));
        var journeys = new StubJourneys(journey, updated);
        var tasks = new InMemoryPracticeTasks();
        var tools = new TutorPracticeTools(journeys, tasks);
        var context = context(journey);

        var saved = JSON.readTree(tools.savePracticeTest(context, """
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

        var wrong = JSON.readTree(tools.verifyPracticeTest(context, saved.get("taskId").asLong(), "b"));
        assertFalse(wrong.get("verified").asBoolean());
        assertFalse(wrong.get("choiceCorrect").asBoolean());
        assertEquals(1, tasks.task().attempts().size());

        var correct = JSON.readTree(tools.verifyPracticeTest(context, saved.get("taskId").asLong(), "a"));
        assertTrue(correct.get("verified").asBoolean());
        assertTrue(correct.get("choiceCorrect").asBoolean());
        assertEquals("VERIFIED", tasks.task().status().name());
        assertEquals("COMPLETED", journeys.current().status().name());
    }

    private static TutorContext context(LearningJourney journey) {
        var unit = journey.learnUnit("variables");
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
        var chapter = Chapter.create("basics", "基础", 0);
        var unit = LearnUnit.create(
                "variables",
                "变量与类型",
                "能够声明变量并理解基本类型",
                "## Concept\n变量保存值。\n## Practice\n声明一个带类型的变量。",
                0,
                chapter.code(),
                Set.of()).withId(1L);
        var created = LearningJourney.create(
                1L,
                "typescript",
                "TypeScript 基础",
                List.of(chapter),
                List.of(unit),
                Instant.parse("2026-01-01T00:00:00Z"));
        return created.withPersistedIds(1L, created.chapters(), List.of(unit), created.pathItems());
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
        private PracticeTask task;

        @Override
        public PracticeTask save(PracticeTask candidate) {
            task = candidate.id() == null
                    ? candidate.withPersistedIds(1L, candidate.attempts())
                    : candidate;
            return task;
        }

        @Override
        public Optional<PracticeTask> findById(long id) {
            return task != null && task.id() == id ? Optional.of(task) : Optional.empty();
        }

        @Override
        public List<PracticeTask> findByLearnUnit(long journeyId, long learnUnitId) {
            return task != null && task.journeyId() == journeyId && task.learnUnitId() == learnUnitId
                    ? List.of(task) : List.of();
        }

        private PracticeTask task() {
            return task;
        }
    }
}
