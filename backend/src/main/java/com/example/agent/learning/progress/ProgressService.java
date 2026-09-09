package com.example.agent.learning.progress;

import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.journey.JourneyStatus;
import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.journey.PassReason;
import com.example.agent.learning.path.DeterministicLearningPathPlanner;
import com.example.agent.learning.path.LearningPathItem;
import com.example.agent.learning.path.LearningPathItemStatus;
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

    @Transactional
    public List<LearningPathItem> generatePath(String journeyId) {
        return workflow.execute(
                "GENERATE_PATH",
                () -> {
                    LearningJourney journey = journey(journeyId);
                    List<LearnUnit> learnUnits = repository.listLearnUnitsForJourney(journeyId);
                    List<LearningPathItem> path = planner.plan(journeyId, learnUnits, repository.listPath(journeyId));
                    repository.replacePath(journeyId, path);
                    moveJourneyToPathCurrent(journeyId, path);
                    LearningJourney updated = journey(journeyId);
                    transition(journeyId, journey.status().name(), "GENERATE_PATH", updated.status().name(),
                            Map.of("pathSize", path.size()));
                    return new LearningWorkflowGraph.Action<>("complete", repository.listPath(journeyId));
                },
                Map.of("complete", ignored -> {}));
    }

    @Transactional
    public LearningPathItem startLearnUnit(String journeyId, String learnUnitCode) {
        return workflow.execute(
                "START_LEARN_UNIT",
                () -> {
                    LearningJourney before = journey(journeyId);
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
                    transition(journeyId, before.status().name(), "START_LEARN_UNIT", JourneyStatus.ACTIVE.name(),
                            Map.of("learnUnitCode", learnUnitCode));
                    return new LearningWorkflowGraph.Action<>("complete", started);
                },
                Map.of("complete", ignored -> {}));
    }

    @Transactional
    public LearningPathItem recordDiagnosticResult(
            String journeyId, String learnUnitCode, AssessmentScore score, boolean passed) {
        return workflow.execute(
                "DIAGNOSTIC_RESULT",
                () -> {
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

    @Transactional
    public LearningPathItem recordLearnUnitAssessment(
            String journeyId, String learnUnitCode, AssessmentScore score, boolean passed) {
        LearningJourney before = journey(journeyId);
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
                            transition(journeyId, before.status().name(), "PASS", result.status().name(),
                                    Map.of("learnUnitCode", learnUnitCode, "score", score.totalScore()));
                            moveToNextLearnUnit(journeyId);
                        },
                        "retry", result -> {
                            repository.updateJourney(journeyId, JourneyStatus.ACTIVE, Instant.now());
                            transition(journeyId, before.status().name(), "RETRY", result.status().name(),
                                    Map.of("learnUnitCode", learnUnitCode, "score", score.totalScore()));
                        }));
    }

    @Transactional
    public void markAssessing(String journeyId, String learnUnitCode) {
        workflow.execute(
                "START_ASSESSMENT",
                () -> {
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

    @Transactional
    public void completeLearnUnit(String journeyId, String learnUnitCode) {
        LearningPathItem item = pathItem(journeyId, learnUnitCode);
        repository.updatePathItem(copy(
                item, LearningPathItemStatus.COMPLETED, item.masteryScore(), item.bestAssessmentScore(),
                item.attemptCount(), item.passReason(), item.startedAt(), Instant.now(), item.skippedAt()));
        moveToNextLearnUnit(journeyId);
    }

    @Transactional
    public void markAssessmentFailed(String journeyId, String learnUnitCode) {
        workflow.execute(
                "ASSESSMENT_ERROR",
                () -> {
                    LearningPathItem item = pathItem(journeyId, learnUnitCode);
                    transition(journeyId, item.status().name(), "ASSESSMENT_ERROR", item.status().name(),
                            Map.of("learnUnitCode", learnUnitCode));
                    return new LearningWorkflowGraph.Action<Void>("complete", null);
                },
                Map.of("complete", ignored -> {}));
    }

    @Transactional
    public void skipLearnUnit(String journeyId, String learnUnitCode) {
        LearningJourney before = journey(journeyId);
        LearningPathItem item = pathItem(journeyId, learnUnitCode);
        requireCurrent(item);
        workflow.execute(
                "SKIP",
                () -> {
                    LearningPathItem skipped = copy(
                            item, LearningPathItemStatus.SKIPPED, item.masteryScore(), item.bestAssessmentScore(),
                            item.attemptCount(), null, item.startedAt(), item.passedAt(), Instant.now());
                    repository.updatePathItem(skipped);
                    return new LearningWorkflowGraph.Action<Void>("skip", null);
                },
                Map.of("skip", ignored -> {
                    transition(journeyId, before.status().name(), "SKIP", LearningPathItemStatus.SKIPPED.name(),
                            Map.of("learnUnitCode", learnUnitCode));
                    moveToNextLearnUnit(journeyId);
                }));
    }

    @Transactional
    public void moveToNextLearnUnit(String journeyId) {
        LearningJourney before = journey(journeyId);
        List<LearningPathItem> path = repository.listPath(journeyId);
        repository.resetCurrentPathItems(journeyId);
        LearningPathItem next = path.stream()
                .filter(item -> item.status() == LearningPathItemStatus.PENDING)
                .findFirst()
                .orElse(null);
        if (next == null) {
            repository.updateJourney(journeyId, JourneyStatus.COMPLETED, Instant.now());
            transition(journeyId, before.status().name(), "COMPLETED", JourneyStatus.COMPLETED.name(), Map.of());
            return;
        }
        repository.updatePathItem(copy(
                next, LearningPathItemStatus.CURRENT, next.masteryScore(), next.bestAssessmentScore(),
                next.attemptCount(), next.passReason(), next.startedAt(), next.passedAt(), next.skippedAt()));
        repository.updateJourney(journeyId, JourneyStatus.ACTIVE, Instant.now());
        transition(journeyId, before.status().name(), "NEXT", JourneyStatus.ACTIVE.name(),
                Map.of("learnUnitCode", next.learnUnitCode()));
    }

    public LearningPathItem pathItem(String journeyId, String learnUnitCode) {
        return repository.findPathItem(journeyId, learnUnitCode)
                .orElseThrow(() -> new IllegalArgumentException("LearnUnit is not in the learning path: " + learnUnitCode));
    }

    private LearningJourney journey(String id) {
        return repository.findJourney(id)
                .orElseThrow(() -> new IllegalArgumentException("journey not found: " + id));
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
                masteryScore, bestAssessmentScore, attemptCount, passReason, startedAt, passedAt, skippedAt);
    }

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
