package com.db117.learnagent.learning.application;

import com.db117.learnagent.learning.domain.Answer;
import com.db117.learnagent.learning.domain.Journey;
import com.db117.learnagent.learning.domain.JourneyRepository;
import com.db117.learnagent.learning.domain.Learner;
import com.db117.learnagent.learning.domain.LearnerRepository;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningJourneyRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JourneyApplicationServiceTest {
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

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

        var confirmed = service.confirmPlan(journey.id(), "第一阶段：变量与类型；第二阶段：异步编程");

        assertNotNull(learningJourneys.saved);
        assertEquals("typescript", learningJourneys.saved.languagePackId());
        assertEquals("已确认的规划阶段：\n第一阶段：变量与类型",
                learningJourneys.saved.learnUnits().getFirst().content());
        assertEquals(List.of("first-lesson", "lesson-2"),
                learningJourneys.saved.learnUnits().stream().map(value -> value.code()).toList());
        assertEquals(learningJourneys.saved.id(), confirmed.learningJourneyId());
        assertEquals(learningJourneys.saved.id(), journeys.attachedLearningJourneyId);
    }

    @Test
    void assessmentAndPracticeAdvanceToTheNextMaterializedUnit() {
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

        service.confirmPlan(journey.id(), "第一阶段；第二阶段");
        var afterAssessment = service.recordAssessment(
                journey.id(),
                new JourneyApplicationService.AssessmentSubmission(
                        "first-lesson",
                        List.of(Answer.choice("stage-1", Set.of("第一阶段")))));
        var afterPractice = service.recordPracticeVerified(journey.id(), "first-lesson");

        assertEquals("first-lesson", afterAssessment.currentItem().learnUnitCode());
        assertEquals("lesson-2", afterPractice.currentItem().learnUnitCode());
        assertEquals("ACTIVE", afterPractice.status().name());
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
                () -> service.confirmPlan(journey.id(), "第一阶段"));

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
