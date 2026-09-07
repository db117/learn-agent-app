package com.example.agent.learning.progress;

import com.example.agent.learning.catalog.LearningSkill;
import com.example.agent.learning.journey.JourneyStatus;
import com.example.agent.learning.journey.LearnerSkill;
import com.example.agent.learning.journey.LearnerSkillStatus;
import com.example.agent.learning.journey.LearningJourney;
import com.example.agent.learning.journey.PassReason;
import com.example.agent.learning.path.DeterministicLearningPathPlanner;
import com.example.agent.learning.path.LearningPathItem;
import com.example.agent.learning.path.LearningPathItemStatus;
import com.example.agent.learning.persistence.LearningRepository;
import com.example.agent.learning.scoring.AssessmentScore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能进度和学习路径状态服务。
 *
 * <p>所有状态变更都落到 SQLite，并严格按当前路径节点推进；已完成、已跳过和评估历史不会被覆盖。
 */
@Service
public class ProgressService {

    private final LearningRepository repository;
    private final DeterministicLearningPathPlanner planner;

    public ProgressService(LearningRepository repository, DeterministicLearningPathPlanner planner) {
        this.repository = repository;
        this.planner = planner;
    }

    @Transactional
    public List<LearningPathItem> generatePath(String journeyId) {
        LearningJourney journey = journey(journeyId);
        List<LearningSkill> skills = repository.listSkillsForJourney(journeyId);
        Map<String, LearnerSkill> learnerSkills = new LinkedHashMap<>();
        repository.listLearnerSkills(journeyId).forEach(skill -> learnerSkills.put(skill.skillCode(), skill));
        List<LearningPathItem> path = planner.plan(journeyId, skills, learnerSkills);
        repository.replacePath(journeyId, path);
        moveJourneyToPathCurrent(journeyId, path);
        return repository.listPath(journeyId);
    }

    @Transactional
    public LearningPathItem startSkill(String journeyId, String skillCode) {
        LearningPathItem item = pathItem(journeyId, skillCode);
        requireCurrent(item);
        repository.resetCurrentPathItems(journeyId);
        repository.updatePathItem(journeyId, skillCode, LearningPathItemStatus.CURRENT);
        Instant now = Instant.now();
        LearnerSkill previous = repository.findLearnerSkill(journeyId, skillCode).orElse(defaultSkill(journeyId, skillCode));
        if (previous.status() != LearnerSkillStatus.PASSED && previous.status() != LearnerSkillStatus.SKIPPED) {
            repository.upsertLearnerSkill(new LearnerSkill(
                    journeyId, skillCode, LearnerSkillStatus.LEARNING, previous.masteryScore(),
                    previous.bestAssessmentScore(), previous.attemptCount(), previous.passReason(),
                    previous.startedAt() == null ? now : previous.startedAt(), previous.passedAt(), previous.skippedAt()));
        }
        repository.updateJourney(journeyId, JourneyStatus.ACTIVE, skillCode, now);
        return pathItem(journeyId, skillCode);
    }

    @Transactional
    public LearnerSkill recordDiagnosticResult(
            String journeyId, String skillCode, AssessmentScore score, boolean passed) {
        LearnerSkill previous = repository.findLearnerSkill(journeyId, skillCode).orElse(defaultSkill(journeyId, skillCode));
        Instant now = Instant.now();
        LearnerSkill result = new LearnerSkill(
                journeyId, skillCode, passed ? LearnerSkillStatus.PASSED : LearnerSkillStatus.READY,
                Math.max(previous.masteryScore(), score.totalScore()),
                Math.max(previous.bestAssessmentScore(), score.totalScore()), previous.attemptCount() + 1,
                passed ? PassReason.DIAGNOSTIC : null, previous.startedAt(), passed ? now : null, null);
        repository.upsertLearnerSkill(result);
        return result;
    }

    @Transactional
    public LearnerSkill recordSkillAssessment(
            String journeyId, String skillCode, AssessmentScore score, boolean passed) {
        LearnerSkill previous = repository.findLearnerSkill(journeyId, skillCode).orElse(defaultSkill(journeyId, skillCode));
        Instant now = Instant.now();
        LearnerSkill result = new LearnerSkill(
                journeyId, skillCode, passed ? LearnerSkillStatus.PASSED : LearnerSkillStatus.LEARNING,
                Math.max(previous.masteryScore(), score.totalScore()),
                Math.max(previous.bestAssessmentScore(), score.totalScore()), previous.attemptCount() + 1,
                passed ? PassReason.LEARNING : null, previous.startedAt() == null ? now : previous.startedAt(),
                passed ? now : null, null);
        repository.upsertLearnerSkill(result);
        if (repository.findPathItem(journeyId, skillCode).isPresent()) {
            if (passed) completeSkill(journeyId, skillCode);
            else repository.updateJourney(journeyId, JourneyStatus.ACTIVE, skillCode, now);
        }
        return result;
    }

    @Transactional
    public void markAssessing(String journeyId, String skillCode) {
        LearnerSkill previous = repository.findLearnerSkill(journeyId, skillCode).orElse(defaultSkill(journeyId, skillCode));
        repository.upsertLearnerSkill(new LearnerSkill(
                journeyId, skillCode, LearnerSkillStatus.ASSESSING, previous.masteryScore(),
                previous.bestAssessmentScore(), previous.attemptCount(), previous.passReason(),
                previous.startedAt() == null ? Instant.now() : previous.startedAt(), previous.passedAt(), previous.skippedAt()));
    }

    @Transactional
    public void completeSkill(String journeyId, String skillCode) {
        repository.updatePathItem(journeyId, skillCode, LearningPathItemStatus.COMPLETED);
        moveToNextSkill(journeyId);
    }

    @Transactional
    public void markAssessmentFailed(String journeyId, String skillCode) {
        LearnerSkill previous = learnerSkill(journeyId, skillCode);
        repository.upsertLearnerSkill(new LearnerSkill(
                journeyId, skillCode, LearnerSkillStatus.LEARNING, previous.masteryScore(),
                previous.bestAssessmentScore(), previous.attemptCount(), previous.passReason(),
                previous.startedAt() == null ? Instant.now() : previous.startedAt(), previous.passedAt(), previous.skippedAt()));
    }

    @Transactional
    public void skipSkill(String journeyId, String skillCode) {
        LearningPathItem item = pathItem(journeyId, skillCode);
        requireCurrent(item);
        LearnerSkill previous = repository.findLearnerSkill(journeyId, skillCode).orElse(defaultSkill(journeyId, skillCode));
        repository.upsertLearnerSkill(new LearnerSkill(
                journeyId, skillCode, LearnerSkillStatus.SKIPPED, previous.masteryScore(),
                previous.bestAssessmentScore(), previous.attemptCount(), null, previous.startedAt(),
                previous.passedAt(), Instant.now()));
        repository.updatePathItem(journeyId, skillCode, LearningPathItemStatus.SKIPPED);
        moveToNextSkill(journeyId);
    }

    @Transactional
    public void moveToNextSkill(String journeyId) {
        List<LearningPathItem> path = repository.listPath(journeyId);
        repository.resetCurrentPathItems(journeyId);
        String next = path.stream()
                .filter(item -> item.status() == LearningPathItemStatus.PENDING)
                .map(LearningPathItem::skillCode)
                .findFirst()
                .orElse(null);
        if (next == null) {
            repository.updateJourney(journeyId, JourneyStatus.COMPLETED, null, Instant.now());
            return;
        }
        repository.updatePathItem(journeyId, next, LearningPathItemStatus.CURRENT);
        repository.updateJourney(journeyId, JourneyStatus.ACTIVE, next, Instant.now());
    }

    public LearningPathItem pathItem(String journeyId, String skillCode) {
        return repository.findPathItem(journeyId, skillCode)
                .orElseThrow(() -> new IllegalArgumentException("skill is not in the learning path: " + skillCode));
    }

    public LearnerSkill learnerSkill(String journeyId, String skillCode) {
        return repository.findLearnerSkill(journeyId, skillCode)
                .orElseThrow(() -> new IllegalArgumentException("learner skill not found: " + skillCode));
    }

    private LearningJourney journey(String id) {
        return repository.findJourney(id)
                .orElseThrow(() -> new IllegalArgumentException("journey not found: " + id));
    }

    private void moveJourneyToPathCurrent(String journeyId, List<LearningPathItem> path) {
        String current = path.stream()
                .filter(item -> item.status() == LearningPathItemStatus.CURRENT)
                .map(LearningPathItem::skillCode)
                .findFirst()
                .orElse(null);
        repository.updateJourney(journeyId, current == null ? JourneyStatus.COMPLETED : JourneyStatus.ACTIVE, current, Instant.now());
    }

    private LearnerSkill defaultSkill(String journeyId, String skillCode) {
        return new LearnerSkill(journeyId, skillCode, LearnerSkillStatus.READY, 0, 0, 0, null, null, null, null);
    }

    private void requireCurrent(LearningPathItem item) {
        if (item.status() == LearningPathItemStatus.COMPLETED || item.status() == LearningPathItemStatus.SKIPPED) {
            throw new IllegalArgumentException("skill is already closed: " + item.skillCode());
        }
        if (item.status() != LearningPathItemStatus.CURRENT) {
            throw new IllegalArgumentException("skill is not current: " + item.skillCode());
        }
    }
}
