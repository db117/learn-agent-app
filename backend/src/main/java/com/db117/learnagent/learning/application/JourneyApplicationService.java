package com.db117.learnagent.learning.application;

import com.db117.learnagent.learning.domain.Journey;
import com.db117.learnagent.learning.domain.JourneyRepository;
import com.db117.learnagent.learning.domain.Learner;
import com.db117.learnagent.learning.domain.LearnerRepository;
import com.db117.learnagent.shared.domain.DomainRuleViolation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 本地单用户的 Learner/Journey 引导应用服务；只编排 Domain，不调用 AgentScope 或模型。
 */
@ApplicationScoped
public class JourneyApplicationService {
    private final LearnerRepository learnerRepository;
    private final JourneyRepository journeyRepository;
    private final Clock clock;

    @Inject
    public JourneyApplicationService(LearnerRepository learnerRepository, JourneyRepository journeyRepository) {
        this(learnerRepository, journeyRepository, Clock.systemUTC());
    }

    JourneyApplicationService(
            LearnerRepository learnerRepository,
            JourneyRepository journeyRepository,
            Clock clock) {
        this.learnerRepository = learnerRepository;
        this.journeyRepository = journeyRepository;
        this.clock = clock;
    }

    public OnboardingSnapshot snapshot() {
        var learner = learnerRepository.findCurrent();
        var journeys = learner.map(value -> journeyRepository.findByLearnerId(value.id())).orElseGet(List::of);
        return new OnboardingSnapshot(learner.orElse(null), journeys);
    }

    public Learner saveLearner(String backgroundSummary) {
        try {
            var current = learnerRepository.findCurrent();
            var next = current
                    .map(value -> value.withBackgroundSummary(backgroundSummary))
                    .orElseGet(() -> Learner.create("学习者", backgroundSummary, Instant.now(clock)));
            return learnerRepository.save(next);
        } catch (DomainRuleViolation error) {
            throw LearningRequestException.badRequest("INVALID_LEARNER", error.getMessage());
        }
    }

    public Journey createJourney(String goalDescription) {
        var learner = requireLearner();
        try {
            var created = journeyRepository.save(Journey.create(
                    learner.id(), goalDescription, Instant.now(clock)));
            if (journeyRepository.findCurrentByLearnerId(learner.id()).isEmpty()) {
                return journeyRepository.selectCurrent(created.id(), learner.id());
            }
            return created;
        } catch (DomainRuleViolation error) {
            throw LearningRequestException.badRequest("INVALID_JOURNEY", error.getMessage());
        }
    }

    public Journey selectJourney(long journeyId) {
        var learner = requireLearner();
        try {
            return journeyRepository.selectCurrent(journeyId, learner.id());
        } catch (IllegalStateException error) {
            throw LearningRequestException.conflict("JOURNEY_NOT_SELECTABLE", "只能选择属于当前用户的 ACTIVE Journey");
        }
    }

    public Learner requireLearner() {
        return learnerRepository.findCurrent()
                .orElseThrow(() -> LearningRequestException.conflict(
                        "LEARNER_SETUP_REQUIRED", "请先完成 Learner 设置"));
    }

    public record OnboardingSnapshot(Learner learner, List<Journey> journeys) {
        public OnboardingSnapshot {
            journeys = List.copyOf(journeys == null ? List.of() : journeys);
        }
    }
}
