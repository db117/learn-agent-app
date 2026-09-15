package com.db117.learnagent.agent.application;

import com.db117.learnagent.agent.domain.WorkspaceBinding;
import com.db117.learnagent.learning.domain.LearnerRepository;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningJourneyRepository;
import jakarta.enterprise.context.ApplicationScoped;

/** 从 Learning Domain 装配 Tutor 的只读上下文；不暴露 Repository 给 Agent。 */
@ApplicationScoped
public class TutorContextAssembler {
    private final LearnerRepository learnerRepository;
    private final LearningJourneyRepository journeyRepository;

    public TutorContextAssembler(
            LearnerRepository learnerRepository,
            LearningJourneyRepository journeyRepository) {
        this.learnerRepository = learnerRepository;
        this.journeyRepository = journeyRepository;
    }

    public TutorContext assemble(long learnerId, long journeyId) {
        var learner = learnerRepository.findById(learnerId)
                .orElseThrow(() -> TutorRequestException.notFound(
                        "LEARNER_NOT_FOUND", "学习者不存在"));
        var journey = journeyRepository.findById(journeyId)
                .orElseThrow(() -> TutorRequestException.notFound(
                        "JOURNEY_NOT_FOUND", "学习 Journey 不存在"));
        requireJourneyBelongsToLearner(journey, learnerId);
        if (journey.status() != com.db117.learnagent.learning.domain.LearningJourneyStatus.ACTIVE
                || journey.currentItem() == null) {
            throw TutorRequestException.conflict("JOURNEY_NOT_ACTIVE", "学习 Journey 当前不可用");
        }

        var currentItem = journey.currentItem();
        var currentUnit = journey.learnUnit(currentItem.learnUnitCode());
        var completedCount = (int) journey.pathItems().stream()
                .filter(item -> item.status() == com.db117.learnagent.learning.domain.LearningPathItemStatus.COMPLETED
                        || item.status() == com.db117.learnagent.learning.domain.LearningPathItemStatus.SKIPPED)
                .count();
        return new TutorContext(
                learner.id(),
                learner.displayName(),
                journey.id(),
                journey.title(),
                journey.languagePackId(),
                currentUnit.code(),
                currentUnit.title(),
                currentUnit.objective(),
                currentUnit.content(),
                currentItem.status(),
                currentItem.masteryScore(),
                currentItem.bestScore(),
                currentItem.practiceVerified(),
                currentItem.assessmentPassed(),
                currentItem.attemptCount(),
                completedCount,
                journey.pathItems().size(),
                WorkspaceBinding.agent());
    }

    private void requireJourneyBelongsToLearner(LearningJourney journey, long learnerId) {
        if (journey.learnerId() != learnerId) {
            throw TutorRequestException.notFound("JOURNEY_NOT_FOUND", "学习 Journey 不存在");
        }
    }
}
