package com.example.agent.learning.progress;

import com.example.agent.learning.catalog.LearnUnit;
import com.example.agent.learning.journey.JourneyStatus;
import com.example.agent.learning.journey.LearnerLearnUnit;
import com.example.agent.learning.journey.LearnerLearnUnitStatus;
import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.journey.PassReason;
import com.example.agent.learning.path.DeterministicLearningPathPlanner;
import com.example.agent.learning.path.LearningPathItem;
import com.example.agent.learning.path.LearningPathItemStatus;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.scoring.AssessmentScore;
import com.example.agent.learning.workflow.LearningWorkflowGraph;
import com.example.agent.learning.workflow.WorkflowTransition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LearnUnit 进度和学习路径状态服务。
 *
 * <p>每个用户 Action 通过短生命周期 {@link LearningWorkflowGraph} 执行；状态变更都落到 SQLite，
 * 并严格按当前路径节点推进；已完成、已跳过和评估历史不会被覆盖。
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
                    Map<String, LearnerLearnUnit> learnerLearnUnits = new LinkedHashMap<>();
                    repository.listLearnerLearnUnits(journeyId).forEach(status -> learnerLearnUnits.put(status.learnUnitCode(), status));
                    List<LearningPathItem> path = planner.plan(journeyId, learnUnits, learnerLearnUnits);
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
                    repository.resetCurrentPathItems(journeyId);
                    repository.updatePathItem(journeyId, learnUnitCode, LearningPathItemStatus.CURRENT);
                    Instant now = Instant.now();
                    LearnerLearnUnit previous = repository.findLearnerLearnUnit(journeyId, learnUnitCode).orElse(defaultLearnUnit(journeyId, learnUnitCode));
                    if (previous.status() != LearnerLearnUnitStatus.PASSED && previous.status() != LearnerLearnUnitStatus.SKIPPED) {
                        repository.upsertLearnerLearnUnit(new LearnerLearnUnit(
                                journeyId, learnUnitCode, LearnerLearnUnitStatus.LEARNING, previous.masteryScore(),
                                previous.bestAssessmentScore(), previous.attemptCount(), previous.passReason(),
                                previous.startedAt() == null ? now : previous.startedAt(), previous.passedAt(), previous.skippedAt()));
                    }
                    repository.updateJourney(journeyId, JourneyStatus.ACTIVE, learnUnitCode, now);
                    transition(journeyId, before.status().name(), "START_LEARN_UNIT", JourneyStatus.ACTIVE.name(),
                            Map.of("learnUnitCode", learnUnitCode));
                    return new LearningWorkflowGraph.Action<>("complete", pathItem(journeyId, learnUnitCode));
                },
                Map.of("complete", ignored -> {}));
    }

    @Transactional
    public LearnerLearnUnit recordDiagnosticResult(
            String journeyId, String learnUnitCode, AssessmentScore score, boolean passed) {
        return workflow.execute(
                "DIAGNOSTIC_RESULT",
                () -> {
                    LearnerLearnUnit previous = repository.findLearnerLearnUnit(journeyId, learnUnitCode).orElse(defaultLearnUnit(journeyId, learnUnitCode));
                    Instant now = Instant.now();
                    LearnerLearnUnit result = new LearnerLearnUnit(
                            journeyId, learnUnitCode, passed ? LearnerLearnUnitStatus.PASSED : LearnerLearnUnitStatus.READY,
                            Math.max(previous.masteryScore(), score.totalScore()),
                            Math.max(previous.bestAssessmentScore(), score.totalScore()), previous.attemptCount() + 1,
                            passed ? PassReason.DIAGNOSTIC : null, previous.startedAt(), passed ? now : null, null);
                    repository.upsertLearnerLearnUnit(result);
                    transition(journeyId, previous.status().name(), "DIAGNOSTIC_RESULT",
                            result.status().name(), Map.of("learnUnitCode", learnUnitCode, "score", score.totalScore(), "passed", passed));
                    return new LearningWorkflowGraph.Action<>("complete", result);
                },
                Map.of("complete", ignored -> {}));
    }

    @Transactional
    public LearnerLearnUnit recordLearnUnitAssessment(
            String journeyId, String learnUnitCode, AssessmentScore score, boolean passed) {
        LearningJourney before = journey(journeyId);
        LearnerLearnUnit previous = repository.findLearnerLearnUnit(journeyId, learnUnitCode).orElse(defaultLearnUnit(journeyId, learnUnitCode));
        boolean hasPath = repository.findPathItem(journeyId, learnUnitCode).isPresent();
        return workflow.execute(
                passed ? "PASS" : "RETRY",
                () -> {
                    Instant now = Instant.now();
                    LearnerLearnUnit result = new LearnerLearnUnit(
                            journeyId, learnUnitCode, passed ? LearnerLearnUnitStatus.PASSED : LearnerLearnUnitStatus.LEARNING,
                            Math.max(previous.masteryScore(), score.totalScore()),
                            Math.max(previous.bestAssessmentScore(), score.totalScore()), previous.attemptCount() + 1,
                            passed ? PassReason.LEARNING : null, previous.startedAt() == null ? now : previous.startedAt(),
                            passed ? now : null, null);
                    repository.upsertLearnerLearnUnit(result);
                    return new LearningWorkflowGraph.Action<>(passed ? "pass" : "retry", result);
                },
                Map.of(
                        "pass", ignored -> {
                            transition(journeyId, before.status().name(), "PASS", LearnerLearnUnitStatus.PASSED.name(),
                                    Map.of("learnUnitCode", learnUnitCode, "score", score.totalScore()));
                            if (hasPath) completeLearnUnit(journeyId, learnUnitCode);
                        },
                        "retry", ignored -> {
                            if (hasPath) repository.updateJourney(journeyId, JourneyStatus.ACTIVE, learnUnitCode, Instant.now());
                            transition(journeyId, before.status().name(), "RETRY", JourneyStatus.ACTIVE.name(),
                                    Map.of("learnUnitCode", learnUnitCode, "score", score.totalScore()));
                        }));
    }

    @Transactional
    public void markAssessing(String journeyId, String learnUnitCode) {
        workflow.execute(
                "START_ASSESSMENT",
                () -> {
                    LearningJourney before = journey(journeyId);
                    LearnerLearnUnit previous = repository.findLearnerLearnUnit(journeyId, learnUnitCode).orElse(defaultLearnUnit(journeyId, learnUnitCode));
                    repository.upsertLearnerLearnUnit(new LearnerLearnUnit(
                            journeyId, learnUnitCode, LearnerLearnUnitStatus.ASSESSING, previous.masteryScore(),
                            previous.bestAssessmentScore(), previous.attemptCount(), previous.passReason(),
                            previous.startedAt() == null ? Instant.now() : previous.startedAt(), previous.passedAt(), previous.skippedAt()));
                    transition(journeyId, before.status().name(), "START_ASSESSMENT", LearnerLearnUnitStatus.ASSESSING.name(),
                            Map.of("learnUnitCode", learnUnitCode));
                    return new LearningWorkflowGraph.Action<Void>("complete", null);
                },
                Map.of("complete", ignored -> {}));
    }

    @Transactional
    public void completeLearnUnit(String journeyId, String learnUnitCode) {
        repository.updatePathItem(journeyId, learnUnitCode, LearningPathItemStatus.COMPLETED);
        moveToNextLearnUnit(journeyId);
    }

    @Transactional
    public void markAssessmentFailed(String journeyId, String learnUnitCode) {
        workflow.execute(
                "ASSESSMENT_ERROR",
                () -> {
                    LearnerLearnUnit previous = learnerLearnUnit(journeyId, learnUnitCode);
                    repository.upsertLearnerLearnUnit(new LearnerLearnUnit(
                            journeyId, learnUnitCode, LearnerLearnUnitStatus.LEARNING, previous.masteryScore(),
                            previous.bestAssessmentScore(), previous.attemptCount(), previous.passReason(),
                            previous.startedAt() == null ? Instant.now() : previous.startedAt(), previous.passedAt(), previous.skippedAt()));
                    transition(journeyId, LearnerLearnUnitStatus.ASSESSING.name(), "ASSESSMENT_ERROR",
                            LearnerLearnUnitStatus.LEARNING.name(), Map.of("learnUnitCode", learnUnitCode));
                    return new LearningWorkflowGraph.Action<Void>("complete", null);
                },
                Map.of("complete", ignored -> {}));
    }

    @Transactional
    public void skipLearnUnit(String journeyId, String learnUnitCode) {
        LearningJourney before = journey(journeyId);
        LearningPathItem item = pathItem(journeyId, learnUnitCode);
        requireCurrent(item);
        LearnerLearnUnit previous = repository.findLearnerLearnUnit(journeyId, learnUnitCode).orElse(defaultLearnUnit(journeyId, learnUnitCode));
        workflow.execute(
                "SKIP",
                () -> {
                    repository.upsertLearnerLearnUnit(new LearnerLearnUnit(
                            journeyId, learnUnitCode, LearnerLearnUnitStatus.SKIPPED, previous.masteryScore(),
                            previous.bestAssessmentScore(), previous.attemptCount(), null, previous.startedAt(),
                            previous.passedAt(), Instant.now()));
                    repository.updatePathItem(journeyId, learnUnitCode, LearningPathItemStatus.SKIPPED);
                    return new LearningWorkflowGraph.Action<Void>("skip", null);
                },
                Map.of("skip", ignored -> {
                    transition(journeyId, before.status().name(), "SKIP", LearnerLearnUnitStatus.SKIPPED.name(),
                            Map.of("learnUnitCode", learnUnitCode));
                    moveToNextLearnUnit(journeyId);
                }));
    }

    @Transactional
    public void moveToNextLearnUnit(String journeyId) {
        LearningJourney before = journey(journeyId);
        List<LearningPathItem> path = repository.listPath(journeyId);
        repository.resetCurrentPathItems(journeyId);
        String next = path.stream()
                .filter(item -> item.status() == LearningPathItemStatus.PENDING)
                .map(LearningPathItem::learnUnitCode)
                .findFirst()
                .orElse(null);
        if (next == null) {
            repository.updateJourney(journeyId, JourneyStatus.COMPLETED, null, Instant.now());
            transition(journeyId, before.status().name(), "COMPLETED", JourneyStatus.COMPLETED.name(), Map.of());
            return;
        }
        repository.updatePathItem(journeyId, next, LearningPathItemStatus.CURRENT);
        repository.updateJourney(journeyId, JourneyStatus.ACTIVE, next, Instant.now());
        transition(journeyId, before.status().name(), "NEXT", JourneyStatus.ACTIVE.name(),
                Map.of("learnUnitCode", next));
    }

    public LearningPathItem pathItem(String journeyId, String learnUnitCode) {
        return repository.findPathItem(journeyId, learnUnitCode)
                .orElseThrow(() -> new IllegalArgumentException("LearnUnit is not in the learning path: " + learnUnitCode));
    }

    public LearnerLearnUnit learnerLearnUnit(String journeyId, String learnUnitCode) {
        return repository.findLearnerLearnUnit(journeyId, learnUnitCode)
                .orElseThrow(() -> new IllegalArgumentException("learner LearnUnit not found: " + learnUnitCode));
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
        repository.updateJourney(journeyId, current == null ? JourneyStatus.COMPLETED : JourneyStatus.ACTIVE, current, Instant.now());
    }

    private LearnerLearnUnit defaultLearnUnit(String journeyId, String learnUnitCode) {
        return new LearnerLearnUnit(journeyId, learnUnitCode, LearnerLearnUnitStatus.READY, 0, 0, 0, null, null, null, null);
    }

    private void requireCurrent(LearningPathItem item) {
        if (item.status() == LearningPathItemStatus.COMPLETED || item.status() == LearningPathItemStatus.SKIPPED) {
            throw new IllegalArgumentException("LearnUnit is already closed: " + item.learnUnitCode());
        }
        if (item.status() != LearningPathItemStatus.CURRENT) {
            throw new IllegalArgumentException("LearnUnit is not current: " + item.learnUnitCode());
        }
    }

    private void transition(
            String journeyId, String fromState, String action, String toState, Map<String, Object> payload) {
        try {
            repository.insertWorkflowTransition(new WorkflowTransition(
                    UUID.randomUUID().toString(), journeyId, fromState, action, toState,
                    MAPPER.writeValueAsString(payload), Instant.now()));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to persist workflow transition", error);
        }
    }
}
