package com.db117.learnagent.agent.application;

import com.db117.learnagent.agent.api.TutorSessionMode;
import com.db117.learnagent.agent.domain.WorkspaceBinding;
import com.db117.learnagent.learning.domain.Journey;
import com.db117.learnagent.learning.domain.JourneyRepository;
import com.db117.learnagent.learning.domain.JourneyStatus;
import com.db117.learnagent.learning.domain.LearnerRepository;
import com.db117.learnagent.learning.domain.LearningJourney;
import com.db117.learnagent.learning.domain.LearningJourneyRepository;
import com.db117.learnagent.learning.domain.LearningPathItemStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** 从 Learning Domain 装配 Tutor 的只读上下文；不暴露 Repository 给 Agent。 */
@ApplicationScoped
public class TutorContextAssembler {
    private final LearnerRepository learnerRepository;
    private final JourneyRepository journeyRepository;
    private final LearningJourneyRepository learningJourneyRepository;
    @Inject
    public TutorContextAssembler(
            LearnerRepository learnerRepository,
            JourneyRepository journeyRepository,
            LearningJourneyRepository learningJourneyRepository) {
        this.learnerRepository = learnerRepository;
        this.journeyRepository = journeyRepository;
        this.learningJourneyRepository = learningJourneyRepository;
    }

    public TutorContext assemble(long learnerId, long journeyId) {
        return assemble(learnerId, journeyId, TutorSessionMode.LEARNING);
    }

    public TutorContext assemble(long learnerId, long journeyId, TutorSessionMode mode) {
        TutorSessionMode sessionMode = mode == null ? TutorSessionMode.LEARNING : mode;
        Journey journey = journeyRepository.findById(journeyId)
                .orElseThrow(() -> TutorRequestException.notFound(
                        "JOURNEY_NOT_FOUND", "学习 Journey 不存在"));
        requireJourneyBelongsToLearner(journey, learnerId);
        if (journey.status() != JourneyStatus.ACTIVE) {
            throw TutorRequestException.conflict("JOURNEY_NOT_ACTIVE", "学习 Journey 当前不可用");
        }
        if (sessionMode == TutorSessionMode.PLANNING) {
            return planningContext(learnerId, journey);
        }
        if (journey.learningJourneyId() == null) {
            throw TutorRequestException.conflict("JOURNEY_PATH_NOT_READY", "LearningJourney 尚未生成");
        }
        return learningContext(learnerId, journey, journey.learningJourneyId());
    }

    private TutorContext planningContext(long learnerId, Journey journey) {
        com.db117.learnagent.learning.domain.Learner learner = learnerRepository.findById(learnerId)
                .orElseThrow(() -> TutorRequestException.notFound("LEARNER_NOT_FOUND", "学习者不存在"));
        return new TutorContext(
                learner.id(),
                learner.displayName(),
                learner.backgroundSummary(),
                journey.id(),
                null,
                journey.goalDescription(),
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                0,
                0,
                TutorSessionMode.PLANNING,
                WorkspaceBinding.agent());
    }

    private TutorContext learningContext(long learnerId, Journey parent, long learningJourneyId) {
        LearningJourney journey = learningJourneyRepository.findById(learningJourneyId)
                .orElseThrow(() -> TutorRequestException.notFound("JOURNEY_NOT_FOUND", "学习路径不存在"));
        if (journey.learnerId() != learnerId) {
            throw TutorRequestException.notFound("JOURNEY_NOT_FOUND", "学习路径不存在");
        }
        return buildLearningContext(learnerId, parent, journey);
    }

    private TutorContext buildLearningContext(long learnerId, Journey parent, LearningJourney journey) {
        com.db117.learnagent.learning.domain.Learner learner = learnerRepository.findById(learnerId)
                .orElseThrow(() -> TutorRequestException.notFound(
                        "LEARNER_NOT_FOUND", "学习者不存在"));
        if (journey.learnerId() != learnerId) {
            throw TutorRequestException.notFound("JOURNEY_NOT_FOUND", "学习 Journey 不存在");
        }
        if (journey.status() != com.db117.learnagent.learning.domain.LearningJourneyStatus.ACTIVE
                || journey.currentItem() == null) {
            throw TutorRequestException.conflict("JOURNEY_NOT_ACTIVE", "学习 Journey 当前不可用");
        }

        com.db117.learnagent.learning.domain.LearningPathItem currentItem = journey.currentItem();
        com.db117.learnagent.learning.domain.LearnUnit currentUnit = journey.learnUnit(currentItem.learnUnitCode());
        int completedCount = (int) journey.pathItems().stream()
                .filter(item -> item.status() == LearningPathItemStatus.COMPLETED
                        || item.status() == LearningPathItemStatus.SKIPPED)
                .count();
        return new TutorContext(
                learner.id(),
                learner.displayName(),
                learner.backgroundSummary(),
                parent.id(),
                journey.title(),
                parent.goalDescription(),
                journey.languagePackId(),
                currentUnit.code(),
                currentUnit.title(),
                currentUnit.objective(),
                currentUnit.content(),
                currentItem.status(),
                currentItem.practiceVerified(),
                completedCount,
                journey.pathItems().size(),
                TutorSessionMode.LEARNING,
                WorkspaceBinding.learning(parent.id()));
    }

    private void requireJourneyBelongsToLearner(Journey journey, long learnerId) {
        if (journey.learnerId() != learnerId) {
            throw TutorRequestException.notFound("JOURNEY_NOT_FOUND", "学习 Journey 不存在");
        }
    }
}
