package com.db117.learnagent.learning.application;

import com.db117.learnagent.learning.domain.Chapter;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningJourneyRepository;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LearnUnitContentServiceTest {
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void materializesCurrentUnitContentOnceAndPersistsIt() {
        var journey = LearningJourney.create(
                1,
                "typescript",
                "TypeScript",
                List.of(Chapter.create("basics", "基础", 0)),
                List.of(LearnUnit.create(
                        "variables", "变量", "能够声明变量", "", 0, "basics", Set.of())),
                CREATED_AT).withId(10);
        var repository = new FakeRepository(journey);
        var generator = new StubGenerator();
        var service = new LearnUnitContentService(repository, generator);

        var materialized = service.ensureCurrentContent(journey);
        var reused = service.ensureCurrentContent(materialized);

        assertEquals("generated lesson", materialized.learnUnit("variables").content());
        assertEquals("generated lesson", reused.learnUnit("variables").content());
        assertEquals(1, generator.calls);
        assertEquals("generated lesson", repository.saved.learnUnit("variables").content());
    }

    private static final class StubGenerator extends LearnUnitContentGenerator {
        private int calls;

        private StubGenerator() {
            super((Model) null);
        }

        @Override
        public String generate(LearnUnit unit) {
            calls++;
            return "generated lesson";
        }
    }

    private static final class FakeRepository implements LearningJourneyRepository {
        private LearningJourney current;
        private LearningJourney saved;

        private FakeRepository(LearningJourney current) {
            this.current = current;
        }

        @Override
        public LearningJourney save(LearningJourney journey) {
            saved = journey;
            current = journey;
            return journey;
        }

        @Override
        public void delete(long id) {
        }

        @Override
        public Optional<LearningJourney> findById(long id) {
            return current.id() == id ? Optional.of(current) : Optional.empty();
        }

        @Override
        public Optional<LearningJourney> findActiveByLearnerAndLanguage(long learnerId, String languagePackId) {
            return Optional.empty();
        }
    }
}
