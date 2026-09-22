package com.db117.learnagent.agent.tool;

import com.db117.learnagent.agent.api.TutorSessionMode;
import com.db117.learnagent.agent.application.TutorContext;
import com.db117.learnagent.agent.domain.WorkspaceBinding;
import com.db117.learnagent.learning.application.JourneyApplicationService;
import com.db117.learnagent.learning.application.LearningRequestException;
import com.db117.learnagent.learning.domain.Chapter;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TutorLearningToolsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void validatesAndPersistsCurrentUnitContentOnlyOnce() throws Exception {
        LearningJourney journey = learningJourney("");
        LearningJourney savedJourney = journey.materializeLearnUnitContent(
                "variables",
                "## Concept\n变量保存值。\n\n## Example\nconst answer = 42;\n\n## Practice\n声明一个变量。");
        TutorLearningToolsTest.StubJourneys journeys = new StubJourneys(journey, savedJourney);
        TutorLearningTools tools = new TutorLearningTools(journeys);

        com.fasterxml.jackson.databind.JsonNode result = JSON.readTree(tools.saveLearningContent(context(journey), """
                {"concept":"变量保存值。","example":"const answer = 42;","practice":"声明一个变量。"}
                """));

        assertEquals("variables", result.get("learnUnitCode").asText());
        assertEquals(savedJourney.learnUnit("variables").content(), result.get("content").asText());
        assertEquals(savedJourney, journeys.current());

        com.fasterxml.jackson.databind.JsonNode reused = JSON.readTree(tools.saveLearningContent(
                context(savedJourney), "{\"concept\":\"ignored\",\"example\":\"ignored\",\"practice\":\"ignored\"}"));
        assertEquals(result.get("content").asText(), reused.get("content").asText());
    }

    @Test
    void rejectsIncompleteContent() {
        LearningJourney journey = learningJourney("");
        TutorLearningTools tools = new TutorLearningTools(new StubJourneys(journey, journey));

        LearningRequestException error = assertThrows(LearningRequestException.class,
                () -> tools.saveLearningContent(context(journey), "{\"concept\":\"only\"}"));

        assertEquals("INVALID_LEARNING_CONTENT", error.code());
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

    private static LearningJourney learningJourney(String content) {
        Chapter chapter = Chapter.create("basics", "基础", 0);
        LearnUnit unit = LearnUnit.create(
                "variables", "变量与类型", "能够声明变量并理解基本类型", content, 0, chapter.code(), Set.of())
                .withId(1L);
        LearningJourney created = LearningJourney.create(
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
        private final LearningJourney saved;

        private StubJourneys(LearningJourney current, LearningJourney saved) {
            super(null, null, null);
            this.current = current;
            this.saved = saved;
        }

        @Override
        public LearningJourney learningJourneyFor(long journeyId) {
            return current;
        }

        @Override
        public LearningJourney recordLearnUnitContent(long journeyId, String learnUnitCode, String content) {
            current = saved;
            return current;
        }

        private LearningJourney current() {
            return current;
        }
    }
}
