package com.db117.learnagent.learning.application;

import com.db117.learnagent.learning.domain.Assessment;
import com.db117.learnagent.learning.domain.Chapter;
import com.db117.learnagent.learning.domain.Journey;
import com.db117.learnagent.learning.domain.JourneyRepository;
import com.db117.learnagent.learning.domain.JourneyStatus;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.learning.domain.Learner;
import com.db117.learnagent.learning.domain.LearnerRepository;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningJourneyRepository;
import com.db117.learnagent.learning.domain.Question;
import com.db117.learnagent.shared.domain.DomainRuleViolation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 本地单用户的 Learner/Journey 引导应用服务；只编排 Domain，不调用 AgentScope 或模型。
 */
@ApplicationScoped
public class JourneyApplicationService {
    private static final String TYPESCRIPT_LANGUAGE_PACK = "typescript";
    private static final int MAX_PLAN_LENGTH = 20_000;
    private final LearnerRepository learnerRepository;
    private final JourneyRepository journeyRepository;
    private final LearningJourneyRepository learningJourneyRepository;
    private final Clock clock;

    @Inject
    public JourneyApplicationService(
            LearnerRepository learnerRepository,
            JourneyRepository journeyRepository,
            LearningJourneyRepository learningJourneyRepository) {
        this(learnerRepository, journeyRepository, learningJourneyRepository, Clock.systemUTC());
    }

    JourneyApplicationService(
            LearnerRepository learnerRepository,
            JourneyRepository journeyRepository,
            LearningJourneyRepository learningJourneyRepository,
            Clock clock) {
        this.learnerRepository = learnerRepository;
        this.journeyRepository = journeyRepository;
        this.learningJourneyRepository = learningJourneyRepository;
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

    public Journey confirmPlan(long journeyId, String plan) {
        var learner = requireLearner();
        var journey = journeyRepository.findById(journeyId)
                .filter(value -> value.learnerId() == learner.id())
                .orElseThrow(() -> LearningRequestException.notFound(
                        "JOURNEY_NOT_FOUND", "学习 Journey 不存在"));
        if (journey.status() != JourneyStatus.ACTIVE || !journey.current()) {
            throw LearningRequestException.conflict(
                    "JOURNEY_NOT_CURRENT", "只能确认当前 ACTIVE Journey 的规划");
        }
        if (journey.learningJourneyId() != null) {
            return journey;
        }

        var normalizedPlan = normalizePlan(plan);
        try {
            // ponytail: 当前只固化一个首课；完整分阶段解析等规划草稿具备结构化契约后再扩展。
            var learningJourney = learningJourneyRepository.save(
                    createStarterPath(journey, learner.id(), normalizedPlan));
            if (learningJourney.id() == null) {
                throw new IllegalStateException("saved LearningJourney has no id");
            }
            return journeyRepository.attachLearningJourney(journey.id(), learningJourney.id(), learner.id());
        } catch (DomainRuleViolation error) {
            throw LearningRequestException.badRequest("INVALID_LEARNING_PATH", error.getMessage());
        }
    }

    public Learner requireLearner() {
        return learnerRepository.findCurrent()
                .orElseThrow(() -> LearningRequestException.conflict(
                        "LEARNER_SETUP_REQUIRED", "请先完成 Learner 设置"));
    }

    private String normalizePlan(String plan) {
        if (plan == null || plan.isBlank()) {
            throw LearningRequestException.badRequest("INVALID_PLAN", "请先生成规划草稿");
        }
        var normalized = plan.strip();
        if (normalized.length() > MAX_PLAN_LENGTH) {
            throw LearningRequestException.badRequest("INVALID_PLAN", "规划草稿过长");
        }
        return normalized;
    }

    private LearningJourney createStarterPath(Journey journey, long learnerId, String plan) {
        var chapter = Chapter.create("confirmed-plan", "已确认的学习路径", 0);
        var unit = LearnUnit.create(
                "first-lesson",
                "第一课：开始执行学习目标",
                "理解已确认的学习路径，并完成第一个 TypeScript 练习",
                "已确认的规划草稿：\n" + plan,
                0,
                chapter.code(),
                Set.of());
        var assessment = Assessment.create(
                unit.code(),
                70,
                List.of(Question.code(
                        "first-practice",
                        "请根据本课内容，在当前 Workspace 中完成第一个 TypeScript 练习。")));
        return LearningJourney.create(
                learnerId,
                TYPESCRIPT_LANGUAGE_PACK,
                journey.goalDescription(),
                List.of(chapter),
                List.of(unit),
                List.of(assessment),
                Instant.now(clock));
    }

    public record OnboardingSnapshot(Learner learner, List<Journey> journeys) {
        public OnboardingSnapshot {
            journeys = List.copyOf(journeys == null ? List.of() : journeys);
        }
    }
}
