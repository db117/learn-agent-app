package com.db117.learnagent.learning.application;

import com.db117.learnagent.learning.domain.Chapter;
import com.db117.learnagent.learning.domain.Journey;
import com.db117.learnagent.learning.domain.JourneyRepository;
import com.db117.learnagent.learning.domain.JourneyStatus;
import com.db117.learnagent.learning.domain.LearnUnit;
import com.db117.learnagent.learning.domain.Learner;
import com.db117.learnagent.learning.domain.LearnerRepository;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningJourneyRepository;
import com.db117.learnagent.shared.domain.DomainRuleViolation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地单用户的 Learner/Journey 引导应用服务；只编排 Domain，不调用 AgentScope 或模型。
 */
@ApplicationScoped
public class JourneyApplicationService {
    private static final String TYPESCRIPT_LANGUAGE_PACK = "typescript";
    private static final int MAX_PLAN_LENGTH = 20_000;
    private static final int MAX_CONTENT_LENGTH = 12_000;
    private static final ObjectMapper JSON = new ObjectMapper();
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
        var chapters = parsePlan(normalizedPlan);
        LearningJourney saved = null;
        try {
            saved = learningJourneyRepository.save(
                    createLearningJourney(journey, learner.id(), chapters));
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

    /** 保存当前 LearnUnit 的首次教学内容快照；不修改路径进度或 Practice 事实。 */
    public LearningJourney recordLearnUnitContent(
            long journeyId,
            String learnUnitCode,
            String content) {
        if (content == null || content.isBlank() || content.length() > MAX_CONTENT_LENGTH) {
            throw LearningRequestException.badRequest("INVALID_LEARNING_CONTENT", "学习内容不能为空或过长");
        }
        var learningJourney = learningJourneyFor(journeyId);
        try {
            var current = learningJourney.currentItem();
            if (current == null || !current.learnUnitCode().equals(learnUnitCode)) {
                throw new DomainRuleViolation("content must belong to the current LearnUnit");
            }
            var unit = learningJourney.learnUnit(learnUnitCode);
            if (!unit.content().isBlank()) {
                return learningJourney;
            }
            return learningJourneyRepository.save(
                    learningJourney.materializeLearnUnitContent(learnUnitCode, content.strip()));
        } catch (DomainRuleViolation error) {
            throw LearningRequestException.badRequest("INVALID_LEARNING_CONTENT", error.getMessage());
        }
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

    private LearningJourney createLearningJourney(
            Journey journey, long learnerId, List<PlanChapter> planChapters) {
        var chapters = new ArrayList<Chapter>();
        var units = new ArrayList<LearnUnit>();
        String previousUnitCode = null;
        int unitSequence = 0;
        for (int chapterIndex = 0; chapterIndex < planChapters.size(); chapterIndex++) {
            var planChapter = planChapters.get(chapterIndex);
            var chapter = Chapter.create(planChapter.code(), planChapter.title(), chapterIndex);
            chapters.add(chapter);
            for (var planUnit : planChapter.units()) {
                var prerequisites = previousUnitCode == null ? Set.<String>of() : Set.of(previousUnitCode);
                units.add(LearnUnit.create(
                        planUnit.code(),
                        planUnit.title(),
                        planUnit.objective(),
                        "",
                        unitSequence,
                        chapter.code(),
                        prerequisites));
                previousUnitCode = planUnit.code();
                unitSequence++;
            }
        }
        return LearningJourney.create(
                learnerId,
                TYPESCRIPT_LANGUAGE_PACK,
                journey.goalDescription(),
                chapters,
                units,
                Instant.now(clock));
    }

    private List<PlanChapter> parsePlan(String plan) {
        final JsonNode root;
        try {
            root = parseJsonObject(plan);
        } catch (JsonProcessingException error) {
            throw invalidPlan("规划必须是有效的 JSON");
        }
        if (root == null || !root.isObject() || !root.has("chapters") || !root.get("chapters").isArray()) {
            throw invalidPlan("规划必须包含 chapters 数组");
        }
        var chapterNodes = root.get("chapters");
        if (chapterNodes.isEmpty() || chapterNodes.size() > 50) {
            throw invalidPlan("chapters 数量必须在 1 到 50 之间");
        }
        var chapterCodes = new HashSet<String>();
        var unitCodes = new HashSet<String>();
        var chapters = new ArrayList<PlanChapter>();
        int totalUnits = 0;
        for (var chapterNode : chapterNodes) {
            if (!chapterNode.isObject()) {
                throw invalidPlan("每个 chapter 必须是对象");
            }
            var chapterCode = requiredText(chapterNode, "code");
            if (!chapterCode.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || !chapterCodes.add(chapterCode)) {
                throw invalidPlan("chapter code 必须是唯一的小写短横线编码");
            }
            var unitNodes = chapterNode.get("units");
            if (unitNodes == null || !unitNodes.isArray() || unitNodes.isEmpty() || unitNodes.size() > 50) {
                throw invalidPlan("每个 chapter 必须包含 1 到 50 个 units");
            }
            var units = new ArrayList<PlanUnit>();
            for (var unitNode : unitNodes) {
                if (!unitNode.isObject()) {
                    throw invalidPlan("每个 unit 必须是对象");
                }
                var unitCode = requiredText(unitNode, "code");
                if (!unitCode.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || !unitCodes.add(unitCode)) {
                    throw invalidPlan("unit code 必须是全局唯一的小写短横线编码");
                }
                units.add(new PlanUnit(
                        unitCode,
                        requiredText(unitNode, "title"),
                        requiredText(unitNode, "objective")));
                totalUnits++;
            }
            chapters.add(new PlanChapter(chapterCode, requiredText(chapterNode, "title"), List.copyOf(units)));
        }
        if (totalUnits > 50) {
            throw invalidPlan("units 总数不能超过 50");
        }
        return List.copyOf(chapters);
    }

    private JsonNode parseJsonObject(String plan) throws JsonProcessingException {
        try {
            return JSON.readTree(plan);
        } catch (JsonProcessingException error) {
            var start = plan.indexOf('{');
            var end = plan.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw error;
            }
            return JSON.readTree(plan.substring(start, end + 1));
        }
    }

    private String requiredText(JsonNode parent, String field) {
        var value = parent.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalidPlan("字段 " + field + " 必须是非空文本");
        }
        return value.asText().strip();
    }

    private static LearningRequestException invalidPlan(String message) {
        return LearningRequestException.badRequest("INVALID_PLAN", message);
    }

    /**
     * 已确认规划中的一个章节及其有序学习单元。
     *
     * @param code Journey 内稳定的 Chapter 编码
     * @param title 面向学习者展示的章节名称
     * @param units 章节内按规划顺序排列的 LearnUnit
     */
    private record PlanChapter(
            String code,
            String title,
            List<PlanUnit> units) {
    }

    /**
     * 已确认规划中的单个学习单元；教学内容在进入 LearnUnit 后才生成。
     *
     * @param code Journey 内稳定的 LearnUnit 编码
     * @param title 面向学习者展示的单元标题
     * @param objective 当前单元的可验证学习目标
     */
    private record PlanUnit(
            String code,
            String title,
            String objective) {
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

}
