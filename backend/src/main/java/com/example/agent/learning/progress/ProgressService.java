package com.example.agent.learning.progress;

import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.journey.JourneyStatus;
import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.journey.PassReason;
import com.example.agent.learning.path.DeterministicLearningPathPlanner;
import com.example.agent.learning.path.LearningPathItem;
import com.example.agent.learning.path.LearningPathItemStatus;
import com.example.agent.learning.path.LearningPhase;
import com.example.agent.learning.path.GuidedPracticeEntry;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.workflow.LearningWorkflowGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Learning Path 的确定性状态服务。
 *
 * <p>Journey + LearnUnit 的关系、状态、掌握度和历史成绩全部由
 * {@link LearningPathItem} 持久化；TutorAgent 只能读取这些事实。</p>
 */
@Service
public class ProgressService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LearningRepository repository;
    private final DeterministicLearningPathPlanner planner;
    private final LearningWorkflowGraph workflow = new LearningWorkflowGraph();

    public ProgressService(LearningRepository repository, DeterministicLearningPathPlanner planner) {
        this.repository = repository;
        this.planner = planner;
    }

    /**
     * 根据 Journey 当前关联的 LearnUnit 生成确定性学习路径并持久化。
     *
     * <p>规划器只负责计算路径；本方法负责替换数据库中的路径、同步 Journey 当前状态，并记录工作流
     * 转换。</p>
     */
    @Transactional
    public List<LearningPathItem> generatePath(String journeyId) {
        return workflow.execute(
                "GENERATE_PATH",
                () -> {
                    LearningJourney journey = journey(journeyId);
                    var chapters = repository.listChaptersForJourney(journeyId);
                    List<LearnUnit> learnUnits = repository.listLearnUnitsForJourney(journeyId);
                    List<LearningPathItem> path = planner.plan(
                            journeyId, chapters, learnUnits, repository.listPath(journeyId));
                    repository.replacePath(journeyId, path);
                    moveJourneyToPathCurrent(journeyId, path);
                    LearningJourney updated = journey(journeyId);
                    transition(journeyId, journey.status().name(), "GENERATE_PATH", updated.status().name(),
                            Map.of("pathSize", path.size()));
                    return new LearningWorkflowGraph.Action<>("complete", repository.listPath(journeyId));
                },
                Map.of("complete", ignored -> {}));
    }

    /**
     * 将指定的当前节点重新写入为 CURRENT，并同步 Journey 的活动时间。
     *
     * <p>更新前先清除其他 CURRENT 节点，再复制节点的进度事实，最后记录 START_LEARN_UNIT 转换。</p>
     */
    @Transactional
    public LearningPathItem startLearnUnit(String journeyId, String learnUnitCode) {
        return workflow.execute(
                "START_LEARN_UNIT",
                () -> {
                    activeJourney(journeyId);
                    LearningPathItem item = pathItem(journeyId, learnUnitCode);
                    requireCurrent(item);
                    Instant now = Instant.now();
                    repository.resetCurrentPathItems(journeyId);
                    LearningPathItem started = copy(
                            item, LearningPathItemStatus.CURRENT, item.masteryScore(), item.bestAssessmentScore(),
                            item.attemptCount(), item.passReason(), item.startedAt() == null ? now : item.startedAt(),
                            item.passedAt(), item.skippedAt());
                    repository.updatePathItem(started);
                    repository.updateJourney(journeyId, JourneyStatus.ACTIVE, now);
                    transition(journeyId, item.status().name(), "START_LEARN_UNIT", started.status().name(),
                            Map.of("learnUnitCode", learnUnitCode));
                    return new LearningWorkflowGraph.Action<>("complete", started);
                },
                Map.of("complete", ignored -> {}));
    }

    /** 继续当前 LearnUnit，是服务端进入当前学习单元的入口。 */
    @Transactional
    public LearningPathItem continueLearnUnit(String journeyId, String learnUnitCode) {
        return startLearnUnit(journeyId, learnUnitCode);
    }

    /** 只允许推进当前阶段；独立检查由 Assessment 在后续流程中收尾。 */
    @Transactional
    public LearningPathItem advancePhase(String journeyId, String learnUnitCode, LearningPhase expectedPhase) {
        activeJourney(journeyId);
        LearningPathItem item = pathItem(journeyId, learnUnitCode);
        requireCurrent(item);
        requirePhase(item, expectedPhase);
        LearningPhase next = item.learningPhase().next();
        if (next == null) throw new IllegalArgumentException("independent check must be completed before advancing");
        return workflow.execute(
                "ADVANCE_PHASE",
                () -> new LearningWorkflowGraph.Action<>("complete", phase(item, next, item.skippedPhases(), item.guidedPracticeEntries())),
                Map.of("complete", result -> {
                    repository.updatePathItem(result);
                    transition(journeyId, item.learningPhase().name(), "ADVANCE_PHASE", result.learningPhase().name(),
                            Map.of("learnUnitCode", learnUnitCode));
                }));
    }

    /** 跳过当前阶段并持久化跳过事实；跳过独立检查不会关闭 LearnUnit。 */
    @Transactional
    public LearningPathItem skipPhase(String journeyId, String learnUnitCode, LearningPhase expectedPhase) {
        activeJourney(journeyId);
        LearningPathItem item = pathItem(journeyId, learnUnitCode);
        requireCurrent(item);
        requirePhase(item, expectedPhase);
        if (item.skippedPhases().contains(item.learningPhase())) {
            throw new IllegalArgumentException("learning phase was already skipped: " + item.learningPhase());
        }
        List<LearningPhase> skipped = new java.util.ArrayList<>(item.skippedPhases());
        skipped.add(item.learningPhase());
        LearningPhase next = item.learningPhase().next();
        LearningPhase resultingPhase = next == null ? item.learningPhase() : next;
        return workflow.execute(
                "SKIP_PHASE",
                () -> new LearningWorkflowGraph.Action<>("complete", phase(item, resultingPhase, skipped, item.guidedPracticeEntries())),
                Map.of("complete", result -> {
                    repository.updatePathItem(result);
                    transition(journeyId, item.learningPhase().name(), "SKIP_PHASE", result.learningPhase().name(),
                            Map.of("learnUnitCode", learnUnitCode, "skippedPhase", item.learningPhase().name()));
                }));
    }

    /** 保存引导练习回答和反馈；该动作不创建 Assessment、Attempt 或分数。 */
    @Transactional
    public LearningPathItem recordGuidedPractice(String journeyId, String learnUnitCode, String response) {
        activeJourney(journeyId);
        LearningPathItem item = pathItem(journeyId, learnUnitCode);
        requireCurrent(item);
        requirePhase(item, LearningPhase.GUIDED_PRACTICE);
        if (response == null || response.isBlank() || response.length() > 5000) {
            throw new IllegalArgumentException("guided practice response must contain 1 to 5000 characters");
        }
        List<GuidedPracticeEntry> entries = new java.util.ArrayList<>(item.guidedPracticeEntries());
        entries.add(new GuidedPracticeEntry(
                response.trim(), "已记录你的练习，可以继续到独立检查。", Instant.now()));
        return workflow.execute(
                "GUIDED_PRACTICE",
                () -> new LearningWorkflowGraph.Action<>("complete", phase(
                        item, item.learningPhase(), item.skippedPhases(), entries)),
                Map.of("complete", result -> {
                    repository.updatePathItem(result);
                    transition(journeyId, item.learningPhase().name(), "GUIDED_PRACTICE", item.learningPhase().name(),
                            Map.of("learnUnitCode", learnUnitCode));
                }));
    }

    /**
     * 记录初始诊断结果，并根据通过情况更新 LearnUnit 的掌握度、最好成绩和状态。
     *
     * <p>诊断通过会直接关闭节点并记录诊断通过原因；未通过只累积成绩，不提前改变节点状态。</p>
     */
    @Transactional
    public LearningPathItem recordDiagnosticResult(
            String journeyId, String learnUnitCode, AssessmentScore score, boolean passed) {
        return workflow.execute(
                "DIAGNOSTIC_RESULT",
                () -> {
                    activeJourney(journeyId);
                    LearningPathItem previous = pathItem(journeyId, learnUnitCode);
                    if (previous.status() == LearningPathItemStatus.COMPLETED
                            || previous.status() == LearningPathItemStatus.SKIPPED) {
                        throw new IllegalArgumentException("LearnUnit is already closed: " + learnUnitCode);
                    }
                    Instant now = Instant.now();
                    LearningPathItem result = copy(
                            previous,
                            passed ? LearningPathItemStatus.COMPLETED : previous.status(),
                            Math.max(previous.masteryScore(), score.totalScore()),
                            Math.max(previous.bestAssessmentScore(), score.totalScore()),
                            previous.attemptCount() + 1,
                            passed ? PassReason.DIAGNOSTIC : previous.passReason(),
                            previous.startedAt(), passed ? now : previous.passedAt(), previous.skippedAt());
                    repository.updatePathItem(result);
                    transition(journeyId, previous.status().name(), "DIAGNOSTIC_RESULT", result.status().name(),
                            Map.of("learnUnitCode", learnUnitCode, "score", score.totalScore(), "passed", passed));
                    return new LearningWorkflowGraph.Action<>("complete", result);
                },
                Map.of("complete", ignored -> {}));
    }

    /**
     * 记录 LearnUnit 评估结果，并通过工作流路由到 PASS 或 RETRY。
     *
     * <p>通过时推进到下一个节点；未通过时保留当前节点并刷新 Journey 活动时间。</p>
     */
    @Transactional
    public LearningPathItem recordLearnUnitAssessment(
            String journeyId, String learnUnitCode, AssessmentScore score, boolean passed) {
        activeJourney(journeyId);
        LearningPathItem previous = pathItem(journeyId, learnUnitCode);
        requireCurrent(previous);
        return workflow.execute(
                passed ? "PASS" : "RETRY",
                () -> {
                    Instant now = Instant.now();
                    LearningPathItem result = copy(
                            previous,
                            passed ? LearningPathItemStatus.COMPLETED : LearningPathItemStatus.CURRENT,
                            Math.max(previous.masteryScore(), score.totalScore()),
                            Math.max(previous.bestAssessmentScore(), score.totalScore()),
                            previous.attemptCount() + 1,
                            passed ? PassReason.LEARNING : previous.passReason(),
                            previous.startedAt() == null ? now : previous.startedAt(),
                            passed ? now : previous.passedAt(), previous.skippedAt());
                    repository.updatePathItem(result);
                    return new LearningWorkflowGraph.Action<>(passed ? "pass" : "retry", result);
                },
                Map.of(
                        "pass", result -> {
                            transition(journeyId, previous.status().name(), "PASS", result.status().name(),
                                    Map.of("learnUnitCode", learnUnitCode, "score", score.totalScore()));
                            advanceToNextLearnUnit(journeyId, learnUnitCode, result.status());
                        },
                        "retry", result -> {
                            repository.updateJourney(journeyId, JourneyStatus.ACTIVE, Instant.now());
                            transition(journeyId, previous.status().name(), "RETRY", result.status().name(),
                                    Map.of("learnUnitCode", learnUnitCode, "score", score.totalScore()));
                        }));
    }

    /** 将当前 LearnUnit 标记为正在评估，并记录开始评估的工作流转换。 */
    @Transactional
    public void markAssessing(String journeyId, String learnUnitCode) {
        workflow.execute(
                "START_ASSESSMENT",
                () -> {
                    activeJourney(journeyId);
                    LearningPathItem item = pathItem(journeyId, learnUnitCode);
                    requireCurrent(item);
                    Instant now = Instant.now();
                    LearningPathItem started = copy(
                            item, LearningPathItemStatus.CURRENT, item.masteryScore(), item.bestAssessmentScore(),
                            item.attemptCount(), item.passReason(), item.startedAt() == null ? now : item.startedAt(),
                            item.passedAt(), item.skippedAt());
                    repository.updatePathItem(started);
                    transition(journeyId, item.status().name(), "START_ASSESSMENT", started.status().name(),
                            Map.of("learnUnitCode", learnUnitCode));
                    return new LearningWorkflowGraph.Action<Void>("complete", null);
                },
                Map.of("complete", ignored -> {}));
    }

    /** 记录评估异常，不改变当前节点状态，交由调用方决定后续重试。 */
    @Transactional
    public void markAssessmentFailed(String journeyId, String learnUnitCode) {
        workflow.execute(
                "ASSESSMENT_ERROR",
                () -> {
                    activeJourney(journeyId);
                    LearningPathItem item = pathItem(journeyId, learnUnitCode);
                    transition(journeyId, item.status().name(), "ASSESSMENT_ERROR", item.status().name(),
                            Map.of("learnUnitCode", learnUnitCode));
                    return new LearningWorkflowGraph.Action<Void>("complete", null);
                },
                Map.of("complete", ignored -> {}));
    }

    /**
     * 跳过当前 LearnUnit，并在没有未完成评估时推进学习路径。
     *
     * <p>跳过只记录为 SKIPPED，不会伪造掌握度或通过原因。</p>
     */
    @Transactional
    public void skipLearnUnit(String journeyId, String learnUnitCode) {
        activeJourney(journeyId);
        LearningPathItem item = pathItem(journeyId, learnUnitCode);
        requireCurrent(item);
        if (repository.hasOpenLearnUnitAttempt(journeyId, learnUnitCode)) {
            throw new IllegalArgumentException("finish the current assessment before skipping: " + learnUnitCode);
        }
        workflow.execute(
                "SKIP",
                () -> {
                    LearningPathItem skipped = copy(
                            item, LearningPathItemStatus.SKIPPED, item.masteryScore(), item.bestAssessmentScore(),
                            item.attemptCount(), null, item.startedAt(), null, Instant.now());
                    repository.updatePathItem(skipped);
                    return new LearningWorkflowGraph.Action<Void>("skip", null);
                },
                Map.of("skip", ignored -> {
                    transition(journeyId, item.status().name(), "SKIP", LearningPathItemStatus.SKIPPED.name(),
                            Map.of("learnUnitCode", learnUnitCode));
                    advanceToNextLearnUnit(journeyId, learnUnitCode, LearningPathItemStatus.SKIPPED);
                }));
    }

    /**
     * 在一个已关闭节点之后返回服务端选出的下一个节点。
     *
     * <p>正常 PASS/SKIP 流程已经原子推进，因此该入口只负责幂等地返回已有 CURRENT 节点，或在尚未推进
     * 时执行一次推进。</p>
     */
    @Transactional
    public LearningPathItem nextLearnUnit(String journeyId, String closedLearnUnitCode) {
        return workflow.execute(
                "NEXT",
                () -> {
                    LearningJourney journey = journey(journeyId);
                    if (journey.status() == JourneyStatus.ARCHIVED) {
                        throw new IllegalArgumentException("journey is archived: " + journeyId);
                    }
                    LearningPathItem closed = pathItem(journeyId, closedLearnUnitCode);
                    if (closed.status() != LearningPathItemStatus.COMPLETED
                            && closed.status() != LearningPathItemStatus.SKIPPED) {
                        throw new IllegalArgumentException("LearnUnit must be closed before moving next: " + closedLearnUnitCode);
                    }
                    List<LearningPathItem> path = repository.listPath(journeyId);
                    LearningPathItem current = path.stream()
                            .filter(item -> item.status() == LearningPathItemStatus.CURRENT)
                            .findFirst()
                            .orElse(null);
                    if (current != null || journey.status() == JourneyStatus.COMPLETED) {
                        return new LearningWorkflowGraph.Action<>("complete", current == null ? closed : current);
                    }
                    return new LearningWorkflowGraph.Action<>(
                            "advance", advanceToNextLearnUnit(journeyId, closedLearnUnitCode, closed.status()));
                },
                Map.of("complete", ignored -> {}, "advance", ignored -> {}));
    }

    /** 仅在调用方已经关闭当前节点后推进学习路径。 */
    @Transactional
    public void moveToNextLearnUnit(String journeyId) {
        workflow.execute(
                "NEXT",
                () -> {
                    activeJourney(journeyId);
                    List<LearningPathItem> path = repository.listPath(journeyId);
                    if (path.stream().anyMatch(item -> item.status() == LearningPathItemStatus.CURRENT)) {
                        throw new IllegalArgumentException("current LearnUnit must be closed before moving next");
                    }
                    return new LearningWorkflowGraph.Action<>(
                            "complete", advanceToNextLearnUnit(journeyId, null, LearningPathItemStatus.COMPLETED));
                },
                Map.of("complete", ignored -> {}));
    }

    /** 在创建评估重试前检查服务端持有的当前节点状态。 */
    public LearningPathItem requireCurrentLearnUnit(String journeyId, String learnUnitCode) {
        activeJourney(journeyId);
        LearningPathItem item = pathItem(journeyId, learnUnitCode);
        requireCurrent(item);
        return item;
    }

    public LearningPathItem pathItem(String journeyId, String learnUnitCode) {
        return repository.findPathItem(journeyId, learnUnitCode)
                .orElseThrow(() -> new IllegalArgumentException("LearnUnit is not in the learning path: " + learnUnitCode));
    }

    private LearningJourney journey(String id) {
        return repository.findJourney(id)
                .orElseThrow(() -> new IllegalArgumentException("journey not found: " + id));
    }

    private LearningJourney activeJourney(String id) {
        LearningJourney journey = journey(id);
        if (journey.status() != JourneyStatus.ACTIVE) {
            throw new IllegalArgumentException("journey is not active: " + id);
        }
        return journey;
    }

    /**
     * 将第一个 PENDING 节点设为 CURRENT；没有下一个节点时完成 Journey。
     *
     * <p>调用方已经完成当前节点的关闭，本方法负责清理旧的 CURRENT 状态、更新 Journey，并记录 NEXT
     * 转换。</p>
     */
    private LearningPathItem advanceToNextLearnUnit(
            String journeyId, String closedLearnUnitCode, LearningPathItemStatus closedStatus) {
        LearningJourney before = journey(journeyId);
        List<LearningPathItem> path = repository.listPath(journeyId);
        repository.resetCurrentPathItems(journeyId);
        LearningPathItem next = path.stream()
                .filter(item -> item.status() == LearningPathItemStatus.PENDING)
                .findFirst()
                .orElse(null);
        if (next == null) {
            if (before.status() != JourneyStatus.COMPLETED) {
                repository.updateJourney(journeyId, JourneyStatus.COMPLETED, Instant.now());
                transition(journeyId, before.status().name(), "COMPLETED", JourneyStatus.COMPLETED.name(),
                        Map.of("closedLearnUnitCode", closedLearnUnitCode == null ? "" : closedLearnUnitCode));
            }
            return null;
        }
        repository.updatePathItem(copy(
                next, LearningPathItemStatus.CURRENT, next.masteryScore(), next.bestAssessmentScore(),
                next.attemptCount(), next.passReason(), next.startedAt(), next.passedAt(), next.skippedAt()));
        repository.updateJourney(journeyId, JourneyStatus.ACTIVE, Instant.now());
        transition(journeyId, next.status().name(), "NEXT", LearningPathItemStatus.CURRENT.name(),
                Map.of("learnUnitCode", next.learnUnitCode(),
                        "closedLearnUnitCode", closedLearnUnitCode == null ? "" : closedLearnUnitCode,
                        "closedState", closedStatus.name()));
        return next;
    }

    private void moveJourneyToPathCurrent(String journeyId, List<LearningPathItem> path) {
        String current = path.stream()
                .filter(item -> item.status() == LearningPathItemStatus.CURRENT)
                .map(LearningPathItem::learnUnitCode)
                .findFirst()
                .orElse(null);
        repository.updateJourney(
                journeyId, current == null ? JourneyStatus.COMPLETED : JourneyStatus.ACTIVE, Instant.now());
    }

    private void requireCurrent(LearningPathItem item) {
        if (item.status() == LearningPathItemStatus.COMPLETED || item.status() == LearningPathItemStatus.SKIPPED) {
            throw new IllegalArgumentException("LearnUnit is already closed: " + item.learnUnitCode());
        }
        if (item.status() != LearningPathItemStatus.CURRENT) {
            throw new IllegalArgumentException("LearnUnit is not current: " + item.learnUnitCode());
        }
    }

    private void requirePhase(LearningPathItem item, LearningPhase expectedPhase) {
        if (expectedPhase == null || item.learningPhase() != expectedPhase) {
            throw new IllegalArgumentException("LearnUnit phase is not current: " + item.learningPhase());
        }
    }

    private LearningPathItem phase(
            LearningPathItem item,
            LearningPhase learningPhase,
            List<LearningPhase> skippedPhases,
            List<GuidedPracticeEntry> guidedPracticeEntries) {
        return new LearningPathItem(
                item.id(), item.journeyId(), item.learnUnitCode(), item.sequence(), item.status(),
                item.masteryScore(), item.bestAssessmentScore(), item.attemptCount(), item.passReason(),
                item.startedAt(), item.passedAt(), item.skippedAt(), learningPhase, skippedPhases,
                guidedPracticeEntries);
    }

    /**
     * 复制路径节点的稳定身份，只替换调用方传入的进度快照字段。
     *
     * @param item 原路径节点
     * @param status 新节点状态
     * @param masteryScore 新掌握度
     * @param bestAssessmentScore 新的历史最高评估分数
     * @param attemptCount 新的评估次数
     * @param passReason 新的通过原因
     * @param startedAt 首次开始时间
     * @param passedAt 最近通过时间
     * @param skippedAt 跳过时间
     * @return 保留原身份和顺序的新路径节点
     */
    private LearningPathItem copy(
            LearningPathItem item,
            LearningPathItemStatus status,
            int masteryScore,
            int bestAssessmentScore,
            int attemptCount,
            PassReason passReason,
            Instant startedAt,
            Instant passedAt,
            Instant skippedAt) {
        return new LearningPathItem(
                item.id(), item.journeyId(), item.learnUnitCode(), item.sequence(), status,
                masteryScore, bestAssessmentScore, attemptCount, passReason, startedAt, passedAt, skippedAt,
                item.learningPhase(), item.skippedPhases(), item.guidedPracticeEntries());
    }

    /**
     * 将一次确定性的学习状态转换序列化后写入 workflow_transition。
     *
     * @param journeyId Journey 标识
     * @param fromState 转换前状态
     * @param action 执行的动作
     * @param toState 转换后状态
     * @param payload 转换附带的确定性事实
     */
    private void transition(
            String journeyId, String fromState, String action, String toState, Map<String, Object> payload) {
        try {
            repository.insertWorkflowTransition(new com.example.agent.learning.workflow.WorkflowTransition(
                    UUID.randomUUID().toString(), journeyId, fromState, action, toState,
                    MAPPER.writeValueAsString(payload), Instant.now()));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to persist workflow transition", error);
        }
    }
}
