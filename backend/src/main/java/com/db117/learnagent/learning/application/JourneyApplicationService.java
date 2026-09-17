package com.db117.learnagent.learning.application;

import com.db117.learnagent.learning.domain.Answer;
import com.db117.learnagent.learning.domain.Assessment;
import com.db117.learnagent.learning.domain.AssessmentAttempt;
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
import com.db117.learnagent.learning.domain.QuestionType;
import com.db117.learnagent.shared.domain.DomainRuleViolation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        var learningJourneySummaries = new HashMap<Long, LearningJourneySummary>();
        for (var journey : journeys) {
            if (journey.learningJourneyId() == null) {
                continue;
            }
            learningJourneyRepository.findById(journey.learningJourneyId())
                    .ifPresent(learningJourney -> learningJourneySummaries.put(
                            journey.id(),
                            new LearningJourneySummary(
                                    learningJourney.status().name(),
                                    learningJourney.currentItem() == null
                                            ? null
                                            : learningJourney.currentItem().learnUnitCode())));
        }
        return new OnboardingSnapshot(learner.orElse(null), journeys, learningJourneySummaries);
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
        var journey = ownedJourney(journeyId, learner.id());
        if (journey.status() != JourneyStatus.ACTIVE || !journey.current()) {
            throw LearningRequestException.conflict(
                    "JOURNEY_NOT_CURRENT", "只能确认当前 ACTIVE Journey 的规划");
        }
        if (journey.learningJourneyId() != null) {
            return journey;
        }

        var normalizedPlan = normalizePlan(plan);
        LearningJourney saved = null;
        try {
            saved = learningJourneyRepository.save(
                    createLearningJourney(journey, learner.id(), normalizedPlan));
            if (saved.id() == null) {
                throw new IllegalStateException("saved LearningJourney has no id");
            }
            return journeyRepository.attachLearningJourney(journey.id(), saved.id(), learner.id());
        } catch (DomainRuleViolation error) {
            discardUnlinkedLearningJourney(saved);
            throw LearningRequestException.badRequest("INVALID_LEARNING_PATH", error.getMessage());
        } catch (RuntimeException error) {
            discardUnlinkedLearningJourney(saved);
            throw LearningRequestException.internal(
                    "LEARNING_PATH_LINK_FAILED", "学习路径保存失败，请重试");
        }
    }

    /** 返回当前用户 Journey 已确认的 LearningJourney；应用层统一校验所有权。 */
    public LearningJourney learningJourneyFor(long journeyId) {
        var learner = requireLearner();
        var journey = ownedJourney(journeyId, learner.id());
        if (journey.learningJourneyId() == null) {
            throw LearningRequestException.conflict(
                    "JOURNEY_PATH_NOT_READY", "LearningJourney 尚未生成");
        }
        return learningJourneyRepository.findById(journey.learningJourneyId())
                .filter(value -> value.learnerId() == learner.id())
                .orElseThrow(() -> LearningRequestException.notFound(
                        "LEARNING_JOURNEY_NOT_FOUND", "学习路径不存在"));
    }

    /** 将通过的 Practice 证据写回 Learning Domain，并按领域规则推进当前单元。 */
    public LearningJourney recordPracticeVerified(long journeyId, String learnUnitCode) {
        var learningJourney = learningJourneyFor(journeyId);
        try {
            var current = learningJourney.currentItem();
            if (current == null || !current.learnUnitCode().equals(learnUnitCode)) {
                throw new DomainRuleViolation("practice evidence must belong to the current LearnUnit");
            }
            return learningJourneyRepository.save(
                    learningJourney.recordPracticeVerified(learnUnitCode, Instant.now(clock)));
        } catch (DomainRuleViolation error) {
            throw LearningRequestException.badRequest("INVALID_PRACTICE_PROGRESS", error.getMessage());
        }
    }

    /** 接收答案并由 Learning Domain 前的确定性策略计算分数；Tutor/UI 不得提交 score。 */
    public LearningJourney recordAssessment(long journeyId, AssessmentSubmission submission) {
        if (submission == null) {
            throw LearningRequestException.badRequest("INVALID_ASSESSMENT", "评估提交不能为空");
        }
        var learningJourney = learningJourneyFor(journeyId);
        try {
            var current = learningJourney.currentItem();
            if (current == null || !current.learnUnitCode().equals(submission.learnUnitCode())) {
                throw new DomainRuleViolation("assessment must belong to the current LearnUnit");
            }
            var assessment = learningJourney.assessmentFor(submission.learnUnitCode());
            var answers = validateAnswers(assessment, submission.answers());
            var evaluatedAt = Instant.now(clock);
            var attempt = AssessmentAttempt.submitted(
                            learningJourney.id(), submission.learnUnitCode(), answers, evaluatedAt)
                    .evaluate(score(assessment, answers), evaluatedAt);
            return learningJourneyRepository.save(learningJourney.recordAssessmentAttempt(attempt));
        } catch (DomainRuleViolation error) {
            throw LearningRequestException.badRequest("INVALID_ASSESSMENT", error.getMessage());
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

    private LearningJourney createLearningJourney(Journey journey, long learnerId, String plan) {
        var chapter = Chapter.create("confirmed-plan", "已确认的学习路径", 0);
        var segments = planSegments(plan);
        var units = new ArrayList<LearnUnit>();
        var assessments = new ArrayList<Assessment>();
        for (int index = 0; index < segments.size(); index++) {
            var segment = segments.get(index);
            var code = index == 0 ? "first-lesson" : "lesson-" + (index + 1);
            var prerequisites = index == 0 ? Set.<String>of() : Set.of(units.get(index - 1).code());
            var unit = LearnUnit.create(
                    code,
                    "第" + (index + 1) + "单元：" + segment,
                    "掌握本单元：" + segment,
                    "已确认的规划阶段：\n" + segment,
                    index,
                    chapter.code(),
                    prerequisites);
            units.add(unit);
            assessments.add(Assessment.create(
                    unit.code(),
                    70,
                    List.of(Question.singleChoice(
                            "stage-" + (index + 1),
                            "当前 LearnUnit 对应的规划阶段是？",
                            segments,
                            segment))));
        }
        return LearningJourney.create(
                learnerId,
                TYPESCRIPT_LANGUAGE_PACK,
                journey.goalDescription(),
                List.of(chapter),
                units,
                assessments,
                Instant.now(clock));
    }

    private List<String> planSegments(String plan) {
        // ponytail: 先按换行和分号拆分阶段；规划草稿有结构化 schema 后再替换为正式解析器。
        return Arrays.stream(plan.split("\\R+|[；;]"))
                .map(String::strip)
                .filter(segment -> !segment.isBlank())
                .distinct()
                .toList();
    }

    private List<Answer> validateAnswers(Assessment assessment, List<Answer> answers) {
        var answersByCode = new HashMap<String, Answer>();
        for (var answer : answers) {
            if (answer == null || answersByCode.put(answer.questionCode(), answer) != null) {
                throw new DomainRuleViolation("assessment answers must contain unique question codes");
            }
        }
        var questionCodes = assessment.questions().stream().map(Question::code).toList();
        if (!answersByCode.keySet().equals(Set.copyOf(questionCodes))) {
            throw new DomainRuleViolation("assessment answers must cover every question");
        }
        for (var question : assessment.questions()) {
            if (question.type() == QuestionType.CODE) {
                throw new DomainRuleViolation("CODE assessment requires a deterministic evaluator");
            }
        }
        return assessment.questions().stream().map(question -> answersByCode.get(question.code())).toList();
    }

    private int score(Assessment assessment, List<Answer> answers) {
        var answersByCode = answers.stream().collect(java.util.stream.Collectors.toMap(
                Answer::questionCode, answer -> answer));
        var correct = assessment.questions().stream()
                .filter(question -> question.correctOptionIds()
                        .equals(answersByCode.get(question.code()).selectedOptionIds()))
                .count();
        return (int) Math.round(correct * 100.0 / assessment.questions().size());
    }

    private Journey ownedJourney(long journeyId, long learnerId) {
        return journeyRepository.findById(journeyId)
                .filter(value -> value.learnerId() == learnerId)
                .orElseThrow(() -> LearningRequestException.notFound(
                        "JOURNEY_NOT_FOUND", "学习 Journey 不存在"));
    }

    private void discardUnlinkedLearningJourney(LearningJourney learningJourney) {
        if (learningJourney == null || learningJourney.id() == null) {
            return;
        }
        try {
            learningJourneyRepository.delete(learningJourney.id());
        } catch (RuntimeException ignored) {
            // 清理失败不覆盖原始错误；下次重试仍由 Journey 的空关联状态触发。
        }
    }

    public record OnboardingSnapshot(
            Learner learner,
            List<Journey> journeys,
            Map<Long, LearningJourneySummary> learningJourneys) {
        public OnboardingSnapshot {
            journeys = List.copyOf(journeys == null ? List.of() : journeys);
            learningJourneys = Map.copyOf(learningJourneys == null ? Map.of() : learningJourneys);
        }
    }

    /** Bootstrap 使用的 LearningJourney 只读状态，不把完整 Domain 聚合泄露给 UI。 */
    public record LearningJourneySummary(String status, String currentLearnUnitCode) {
    }

    /** 应用层接收的评估答案；分数和通过状态由本服务确定性计算。 */
    public record AssessmentSubmission(String learnUnitCode, List<Answer> answers) {
        public AssessmentSubmission {
            learnUnitCode = Objects.requireNonNull(learnUnitCode, "learnUnitCode must not be null");
            answers = List.copyOf(answers == null ? List.of() : answers);
        }
    }
}
