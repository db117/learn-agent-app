package com.db117.learnagent.learning.application;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        assertEquals("已确认的规划草稿：\n第一阶段：变量与类型；第二阶段：异步编程",
                learningJourneys.saved.learnUnits().getFirst().content());
        assertEquals(learningJourneys.saved.id(), confirmed.learningJourneyId());
        assertEquals(learningJourneys.saved.id(), journeys.attachedLearningJourneyId);
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

        private FakeJourneyRepository(Journey journey) {
            this.journey = journey;
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
        public Optional<LearningJourney> findById(long id) {
            return saved != null && saved.id() == id ? Optional.of(saved) : Optional.empty();
        }

        @Override
        public Optional<LearningJourney> findActiveByLearnerAndLanguage(long learnerId, String languagePackId) {
            return Optional.empty();
        }
    }
}
