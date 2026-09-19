package com.db117.learnagent.learning.application;

import com.db117.learnagent.learning.domain.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JourneyApplicationServiceTest {
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String PLAN = """
            {
              "chapters": [
                {
                  "code": "basics",
                  "title": "基础",
                  "units": [
                    {
                      "code": "variables",
                      "title": "变量与类型",
                      "objective": "能够声明变量并理解基本类型",
                      "concept": "变量保存数据，类型描述数据形状。",
                      "example": "export const answer: number = 42;",
                      "practice": "修复 src/index.ts 中的类型错误。"
                    },
                    {
                      "code": "functions",
                      "title": "函数",
                      "objective": "能够声明带类型的函数",
                      "concept": "函数描述可复用的行为。",
                      "example": "const add = (a: number, b: number) => a + b;",
                      "practice": "为函数补充参数和返回值类型。"
                    }
                  ]
                }
              ]
            }
            """;

    private static final String CHAPTER_PLAN = """
            {
              "chapters": [
                {
                  "code": "foundations",
                  "title": "基础",
                  "units": [
                    {
                      "code": "variables",
                      "title": "变量",
                      "objective": "能够声明变量",
                      "concept": "变量保存数据。",
                      "example": "const answer: number = 42;",
                      "practice": "完成变量练习。"
                    },
                    {
                      "code": "types",
                      "title": "类型",
                      "objective": "能够使用类型",
                      "concept": "类型描述数据形状。",
                      "example": "type User = { name: string };",
                      "practice": "完成类型练习。"
                    }
                  ]
                },
                {
                  "code": "functions",
                  "title": "函数",
                  "units": [
                    {
                      "code": "parameters",
                      "title": "参数",
                      "objective": "能够声明参数",
                      "concept": "参数把数据传入函数。",
                      "example": "const add = (a: number) => a;",
                      "practice": "完成参数练习。"
                    },
                    {
                      "code": "returns",
                      "title": "返回值",
                      "objective": "能够声明返回值",
                      "concept": "返回值描述函数结果。",
                      "example": "const answer = (): number => 42;",
                      "practice": "完成返回值练习。"
                    }
                  ]
                },
                {
                  "code": "async",
                  "title": "异步",
                  "units": [
                    {
                      "code": "promise",
                      "title": "Promise",
                      "objective": "能够使用 Promise",
                      "concept": "Promise 表示异步结果。",
                      "example": "const ready: Promise<boolean> = Promise.resolve(true);",
                      "practice": "完成 Promise 练习。"
                    },
                    {
                      "code": "await",
                      "title": "Await",
                      "objective": "能够使用 await",
                      "concept": "await 等待异步结果。",
                      "example": "const value = await ready;",
                      "practice": "完成 await 练习。"
                    }
                  ]
                }
              ]
            }
            """;

    @Test
    void confirmingPlanningDraftCreatesAndAttachesLearningJourney() {
        var learner = new Learner(1L, "学习者", "TypeScript developer", CREATED_AT);
        var journey = new Journey(
                7L, learner.id(), "学习 TypeScript", com.db117.learnagent.learning.domain.JourneyStatus.ACTIVE,
                CREATED_AT, null, null, true);
        var learningJourneys = new FakeLearningJourneyRepository();
        var journeys = new FakeJourneyRepository(journey);
        var service = new JourneyApplicationService(
                new FakeLearnerRepository(learner),
                journeys,
                learningJourneys,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC));

        var confirmed = service.confirmPlan(journey.id(), PLAN);

        assertNotNull(learningJourneys.saved);
        assertEquals("typescript", learningJourneys.saved.languagePackId());
        assertTrue(learningJourneys.saved.learnUnits().getFirst().content().contains("## Concept"));
        assertTrue(learningJourneys.saved.learnUnits().getFirst().content().contains("## Example"));
        assertEquals(List.of("variables", "functions"),
                learningJourneys.saved.learnUnits().stream().map(value -> value.code()).toList());
        assertTrue(learningJourneys.saved.assessments().isEmpty());
        assertEquals(learningJourneys.saved.id(), confirmed.learningJourneyId());
        assertEquals(learningJourneys.saved.id(), journeys.attachedLearningJourneyId);
    }

    @Test
    void confirmingChapterPlanPreservesThreeChaptersAndSixUnits() {
        var learner = new Learner(1L, "学习者", "TypeScript developer", CREATED_AT);
        var journey = new Journey(
                7L, learner.id(), "学习 TypeScript", com.db117.learnagent.learning.domain.JourneyStatus.ACTIVE,
                CREATED_AT, null, null, true);
        var learningJourneys = new FakeLearningJourneyRepository();
        var journeys = new FakeJourneyRepository(journey);
        var service = new JourneyApplicationService(
                new FakeLearnerRepository(learner),
                journeys,
                learningJourneys,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC));

        service.confirmPlan(journey.id(), CHAPTER_PLAN);

        assertEquals(List.of("foundations", "functions", "async"),
                learningJourneys.saved.chapters().stream().map(value -> value.code()).toList());
        assertEquals(List.of("variables", "types", "parameters", "returns", "promise", "await"),
                learningJourneys.saved.learnUnits().stream().map(value -> value.code()).toList());
        assertEquals(List.of("foundations", "foundations", "functions", "functions", "async", "async"),
                learningJourneys.saved.learnUnits().stream().map(value -> value.chapterCode()).toList());

        var afterVariables = service.recordPracticeVerified(journey.id(), "variables");
        assertEquals("types", afterVariables.currentItem().learnUnitCode());
        var afterTypes = service.recordPracticeVerified(journey.id(), "types");
        assertEquals("parameters", afterTypes.currentItem().learnUnitCode());
        service.recordPracticeVerified(journey.id(), "parameters");
        var afterReturns = service.recordPracticeVerified(journey.id(), "returns");
        assertEquals("promise", afterReturns.currentItem().learnUnitCode());
        service.recordPracticeVerified(journey.id(), "promise");
        var completed = service.recordPracticeVerified(journey.id(), "await");

        assertEquals("COMPLETED", completed.status().name());
        assertNull(completed.currentItem());
        assertEquals(6, completed.pathItems().stream()
                .filter(item -> item.status().name().equals("COMPLETED"))
                .count());
    }

    @Test
    void practiceAdvancesToTheNextMaterializedUnit() {
        var learner = new Learner(1L, "学习者", "TypeScript developer", CREATED_AT);
        var journey = new Journey(
                7L, learner.id(), "学习 TypeScript", com.db117.learnagent.learning.domain.JourneyStatus.ACTIVE,
                CREATED_AT, null, null, true);
        var learningJourneys = new FakeLearningJourneyRepository();
        var service = new JourneyApplicationService(
                new FakeLearnerRepository(learner),
                new FakeJourneyRepository(journey),
                learningJourneys,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC));

        service.confirmPlan(journey.id(), PLAN);
        var afterPractice = service.recordPracticeVerified(journey.id(), "variables");

        assertEquals("functions", afterPractice.currentItem().learnUnitCode());
        assertEquals("ACTIVE", afterPractice.status().name());
        assertEquals(1, afterPractice.pathItems().stream()
                .filter(item -> item.status().name().equals("COMPLETED"))
                .count());
    }

    @Test
    void failedAttachCleansUpTheNewUnlinkedLearningJourney() {
        var learner = new Learner(1L, "学习者", "TypeScript developer", CREATED_AT);
        var journey = new Journey(
                7L, learner.id(), "学习 TypeScript", com.db117.learnagent.learning.domain.JourneyStatus.ACTIVE,
                CREATED_AT, null, null, true);
        var learningJourneys = new FakeLearningJourneyRepository();
        var service = new JourneyApplicationService(
                new FakeLearnerRepository(learner),
                new FakeJourneyRepository(journey, true),
                learningJourneys,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC));

        var error = assertThrows(LearningRequestException.class,
                () -> service.confirmPlan(journey.id(), PLAN));

        assertEquals("LEARNING_PATH_LINK_FAILED", error.code());
        assertNull(learningJourneys.saved);
    }

    private static final class FakeLearnerRepository implements LearnerRepository {
        private final Learner learner;

        private FakeLearnerRepository(Learner learner) {
            this.learner = learner;
        }

        @Override
        public Learner save(Learner value) {
            return value;
        }

        @Override
        public Optional<Learner> findById(long id) {
            return id == learner.id() ? Optional.of(learner) : Optional.empty();
        }

        @Override
        public Optional<Learner> findCurrent() {
            return Optional.of(learner);
        }
    }

    private static final class FakeJourneyRepository implements JourneyRepository {
        private Journey journey;
        private Long attachedLearningJourneyId;
        private final boolean failAttach;

        private FakeJourneyRepository(Journey journey) {
            this(journey, false);
        }

        private FakeJourneyRepository(Journey journey, boolean failAttach) {
            this.journey = journey;
            this.failAttach = failAttach;
        }

        @Override
        public Journey save(Journey value) {
            journey = value;
            return value;
        }

        @Override
        public Optional<Journey> findById(long id) {
            return journey.id() == id ? Optional.of(journey) : Optional.empty();
        }

        @Override
        public List<Journey> findByLearnerId(long learnerId) {
            return journey.learnerId() == learnerId ? List.of(journey) : List.of();
        }

        @Override
        public Optional<Journey> findCurrentByLearnerId(long learnerId) {
            return journey.current() && journey.learnerId() == learnerId ? Optional.of(journey) : Optional.empty();
        }

        @Override
        public Journey selectCurrent(long journeyId, long learnerId) {
            return journey;
        }

        @Override
        public Journey attachLearningJourney(long journeyId, long learningJourneyId, long learnerId) {
            if (failAttach) {
                throw new IllegalStateException("attach failed");
            }
            attachedLearningJourneyId = learningJourneyId;
            journey = journey.attachLearningJourney(learningJourneyId);
            return journey;
        }
    }

    private static final class FakeLearningJourneyRepository implements LearningJourneyRepository {
        private LearningJourney saved;

        @Override
        public LearningJourney save(LearningJourney value) {
            saved = value.withId(42L);
            return saved;
        }

        @Override
        public void delete(long id) {
            if (saved != null && saved.id() == id) {
                saved = null;
            }
        }

        @Override
        public Optional<LearningJourney> findById(long id) {
            return saved != null && saved.id() == id ? Optional.of(saved) : Optional.empty();
        }

        @Override
        public Optional<LearningJourney> findActiveByLearnerAndLanguage(long learnerId, String languagePackId) {
            return Optional.empty();
        }
    }
}
